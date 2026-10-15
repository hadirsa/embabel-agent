/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.a2a.server

import com.embabel.agent.a2a.example.simple.horoscope.TestHoroscopeService
import com.embabel.agent.a2a.example.simple.horoscope.kotlin.TestStarNewsFinder
import com.embabel.agent.a2a.server.config.FakeAiConfiguration
import com.embabel.agent.a2a.server.config.FakeRankerConfiguration
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import org.a2aproject.sdk.common.A2AHeaders
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils
import org.a2aproject.sdk.grpc.utils.ProtoUtils
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.spec.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the A2A endpoints the way an A2A 1.0 client of the A2A Java SDK does:
 * requests are written and responses read with the SDK's own JSON-RPC utilities.
 */
@SpringBootTest
@ActiveProfiles(value = ["test"])
@AutoConfigureMockMvc(addFilters = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = ["com.embabel.agent.a2a"])
@Import(
    value = [
        FakeAiConfiguration::class,
        FakeRankerConfiguration::class,
    ]
)
class A2AWebIntegrationTest(
    @Autowired
    private val mockMvc: MockMvc,
    @Autowired
    private val agentPlatform: AgentPlatform,
    @Autowired
    private val embabelObjectMapperHolder: EmbabelObjectMapperHolder,
    @Autowired
    private val horoscopeService: TestHoroscopeService,
) {

    @BeforeEach
    fun setup() {
        AgentMetadataReader().createAgentScopes(
            TestStarNewsFinder(
                horoscopeService = horoscopeService,
                wordCount = 100,
                storyCount = 5,
            ),
        ).forEach { agentPlatform.deploy(it) }
    }

    private fun userMessage(): Message = Message.builder()
        .role(Message.Role.ROLE_USER)
        .parts(listOf(TextPart("Hello, agent!")))
        .messageId("msg-123")
        .taskId("task-123")
        .contextId("ctx-123")
        .build()

    private fun sendMessageBody(method: String = A2AMethods.SEND_MESSAGE_METHOD): String =
        JSONRPCUtils.toJsonRPCRequest(
            "msg-123",
            method,
            ProtoUtils.ToProto.sendMessageRequest(MessageSendParams.builder().message(userMessage()).build()),
        )

    private fun postJsonRpc(
        body: String,
        a2aVersion: String? = AgentInterface.CURRENT_PROTOCOL_VERSION,
    ): MvcResult = mockMvc.post("/a2a") {
        contentType = MediaType.APPLICATION_JSON
        content = body
        a2aVersion?.let { header(A2AHeaders.A2A_VERSION, it) }
    }.andReturn()

    private fun errorOf(result: MvcResult): Map<*, *> {
        assertEquals(200, result.response.status, "JSON-RPC errors are returned in the body")
        val response = embabelObjectMapperHolder.get().readValue(result.response.contentAsString, Map::class.java)
        return assertIs<Map<*, *>>(response["error"], "Expected a JSON-RPC error: $response")
    }

    @Nested
    inner class AgentCardTests {
        @Test
        fun `should return A2A 1_0 agent card`() {
            val result = mockMvc.get("/a2a/.well-known/agent.json")
                .andExpect {
                    status().isOk()
                    content { contentType(MediaType.APPLICATION_JSON) }
                }.andReturn()

            val agentCard = JsonUtil.fromJson(result.response.contentAsString, AgentCard::class.java)

            assertNotNull(agentCard.name())
            assertNotNull(agentCard.description())
            val agentInterface = agentCard.supportedInterfaces().single()
            assertEquals(TransportProtocol.JSONRPC.asString(), agentInterface.protocolBinding())
            assertEquals(AgentInterface.CURRENT_PROTOCOL_VERSION, agentInterface.protocolVersion())
            assertTrue(
                agentInterface.url().contains("localhost"),
                "Agent interface url should expose localhost: '${agentInterface.url()}'"
            )
            assertTrue(agentInterface.url().endsWith(":80/a2a"), "Agent interface url should expose port and path: '${agentInterface.url()}'")
            assertEquals("Embabel", agentCard.provider()?.organization())
            assertEquals("https://embabel.com", agentCard.provider()?.url())
            assertEquals(DEFAULT_VERSION, agentCard.version())
            assertEquals("https://embabel.com/docs", agentCard.documentationUrl())
            assertEquals(true, agentCard.capabilities().streaming())
            assertEquals(false, agentCard.capabilities().pushNotifications())
            assertEquals(false, agentCard.capabilities().extendedAgentCard())
            assertEquals(listOf("application/json", "text/plain"), agentCard.defaultInputModes())
            assertEquals(listOf("application/json", "text/plain"), agentCard.defaultOutputModes())
        }
    }

    @Nested
    inner class MessageTests {
        @Test
        fun `should handle message send`() {
            val result = postJsonRpc(sendMessageBody())
            assertEquals(200, result.response.status)
            assertTrue(
                MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(result.response.contentType!!))
            )

            val response = JSONRPCUtils.parseResponseBody(
                result.response.contentAsString,
                A2AMethods.SEND_MESSAGE_METHOD,
            ) as SendMessageResponse

            assertEquals("msg-123", response.id)
            val task = assertIs<Task>(response.result)
            assertEquals("task-123", task.id())
            assertEquals("ctx-123", task.contextId())
            assertEquals(TaskState.TASK_STATE_COMPLETED, task.status().state())
            assertEquals("Hello, agent!", (task.history()?.first()?.parts()?.first() as? TextPart)?.text())
            // The test agent returns a HasContent Writeup: its content is sent as a TextPart
            val artifactParts = task.artifacts()!!.single().parts()
            val content = artifactParts.filterIsInstance<TextPart>().single().text()
            assertTrue(content.isNotBlank(), "Agent output must reach the client")
            assertEquals(1, artifactParts.filterIsInstance<DataPart>().size)
        }

        @Test
        fun `should handle message stream`() {
            val result = mockMvc.post("/a2a") {
                contentType = MediaType.APPLICATION_JSON
                content = sendMessageBody(A2AMethods.SEND_STREAMING_MESSAGE_METHOD)
                header(A2AHeaders.A2A_VERSION, AgentInterface.CURRENT_PROTOCOL_VERSION)
            }.andExpect {
                request { asyncStarted() }
            }.andReturn()
            mockMvc.perform(asyncDispatch(result))

            val events = result.response.contentAsString.lines()
                .filter { it.startsWith("data:") }
                .map { ProtoUtils.FromProto.streamingEventKind(JSONRPCUtils.parseResponseEvent(it.removePrefix("data:"))) }

            assertTrue(events.isNotEmpty(), "Stream should carry events")
            val last = assertIs<TaskStatusUpdateEvent>(events.last())
            assertEquals("task-123", last.taskId())
            assertEquals(TaskState.TASK_STATE_COMPLETED, last.status().state())
        }

        @Test
        fun `should handle subscribe to task request`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-456",
                A2AMethods.SUBSCRIBE_TO_TASK_METHOD,
                ProtoUtils.ToProto.subscribeToTaskRequest(TaskIdParams("task-123")),
            )

            // The task does not exist: we only check the request is routed to the streaming handler
            val result = postJsonRpc(body)
            assertEquals(200, result.response.status)
        }

        @Test
        fun `should reject request without A2A-Version header as unsupported A2A 0_3`() {
            val error = errorOf(postJsonRpc(sendMessageBody(), a2aVersion = null))

            assertEquals(A2AErrorCodes.VERSION_NOT_SUPPORTED.code(), error["code"])
        }

        @Test
        fun `should reject A2A 0_3 method name`() {
            val error = errorOf(postJsonRpc(sendMessageBody(method = "message/send")))

            assertEquals(A2AErrorCodes.METHOD_NOT_FOUND.code(), error["code"])
        }
    }

    @Nested
    @Disabled("GetTask and CancelTask are not implemented yet")
    inner class TaskTests {
        @Test
        fun `should get task`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.GET_TASK_METHOD,
                ProtoUtils.ToProto.getTaskRequest(TaskQueryParams("task-123")),
            )

            val response = JSONRPCUtils.parseResponseBody(
                postJsonRpc(body).response.contentAsString,
                A2AMethods.GET_TASK_METHOD,
            ) as GetTaskResponse

            assertEquals("req-1", response.id)
            assertEquals("task-123", response.result.id())
            assertEquals(TaskState.TASK_STATE_COMPLETED, response.result.status().state())
        }

        @Test
        fun `should cancel task`() {
            val body = JSONRPCUtils.toJsonRPCRequest(
                "req-1",
                A2AMethods.CANCEL_TASK_METHOD,
                ProtoUtils.ToProto.cancelTaskRequest(CancelTaskParams("task-123")),
            )

            val response = JSONRPCUtils.parseResponseBody(
                postJsonRpc(body).response.contentAsString,
                A2AMethods.CANCEL_TASK_METHOD,
            ) as CancelTaskResponse

            assertEquals("req-1", response.id)
            assertEquals("task-123", response.result.id())
            assertEquals(TaskState.TASK_STATE_CANCELED, response.result.status().state())
        }
    }
}
