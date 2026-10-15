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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.a2a.server.AgentCardHandler
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.google.gson.JsonSyntaxException
import tools.jackson.databind.ObjectMapper
import jakarta.servlet.ServletRequest
import org.a2aproject.sdk.common.A2AHeaders
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils
import org.a2aproject.sdk.grpc.utils.ProtoUtils
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse
import org.a2aproject.sdk.jsonrpc.common.wrappers.StreamingJSONRPCRequest
import org.a2aproject.sdk.spec.A2AError
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.InternalError
import org.a2aproject.sdk.spec.InvalidParamsError
import org.a2aproject.sdk.spec.InvalidRequestError
import org.a2aproject.sdk.spec.JSONParseError
import org.a2aproject.sdk.spec.MethodNotFoundError
import org.a2aproject.sdk.spec.VersionNotSupportedError
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Registers A2A endpoints for the agent-to-agent communication protocol.
 * Each AgentCardHandler passed in results in the creation of
 * a distinct endpoint with its own agent card.
 */
@Component
class A2AEndpointRegistrar(
    private val agentCardHandlers: List<AgentCardHandler>,
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping,
    private val embabelObjectMapperHolder: EmbabelObjectMapperHolder,
) {

    private val logger = LoggerFactory.getLogger(A2AEndpointRegistrar::class.java)

    @EventListener
    fun onApplicationReady(event: ApplicationReadyEvent) {
        logger.info("Registering ${agentCardHandlers.size} A2A endpoints")
        agentCardHandlers.forEach { endpoint ->
            registerWebEndpoints(endpoint)
        }
    }

    private fun registerWebEndpoints(agentCardHandler: AgentCardHandler) {
        val endpointPath = "/${agentCardHandler.path}/.well-known/agent.json"
        logger.info(
            "Registering web endpoint under {} for {}",
            endpointPath,
            agentCardHandler.infoString(verbose = true),
        )
        val agentCardGetMapping = RequestMappingInfo.paths(endpointPath)
            .methods(RequestMethod.GET)
            .produces(MediaType.APPLICATION_JSON_VALUE)
            .build()
        val achwf = AgentCardHandlerWebFacade(
            agentCardHandler,
            embabelObjectMapperHolder.get(),
        )
        requestMappingHandlerMapping.registerMapping(
            agentCardGetMapping,
            achwf,
            achwf::class.java.getMethod("agentCard", ServletRequest::class.java),
        )

        val jsonRpcPostMethod = achwf.javaClass.getMethod(
            "handleJsonRpc",
            String::class.java,
            String::class.java,
        )
        val jsonRpcPostMapping = RequestMappingInfo.paths(agentCardHandler.path)
            .methods(RequestMethod.POST)
            .consumes(MediaType.APPLICATION_JSON_VALUE)
            .produces(MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        requestMappingHandlerMapping.registerMapping(
            jsonRpcPostMapping,
            achwf,
            jsonRpcPostMethod,
        )
    }
}

private class AgentCardHandlerWebFacade(
    val agentCardHandler: AgentCardHandler,
    val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(AgentCardHandlerWebFacade::class.java)

    @ResponseBody
    fun agentCard(servletRequest: ServletRequest): ResponseEntity<String> {
        val agentCard = agentCardHandler.agentCard(
            scheme = servletRequest.scheme,
            host = servletRequest.serverName,
            port = servletRequest.serverPort,
        )
        return jsonResponse(JsonUtil.toJson(agentCard))
    }

    /**
     * Handles an A2A 1.0 JSON-RPC request. Requests and responses are read and written
     * with the A2A Java SDK, so the wire format matches A2A 1.0 clients.
     * Errors are returned as JSON-RPC error responses.
     */
    @ResponseBody
    fun handleJsonRpc(
        @RequestBody body: String,
        @RequestHeader(name = A2AHeaders.A2A_VERSION, required = false) a2aVersion: String?,
    ): Any {
        return try {
            checkProtocolVersion(a2aVersion)
            val request = JSONRPCUtils.parseRequestBody(body, null)
            logger.info("Received JSON-RPC request: method='{}', id='{}'", request.method, request.id)
            when (request) {
                is StreamingJSONRPCRequest<*> -> agentCardHandler.handleJsonRpcStream(request)
                is NonStreamingJSONRPCRequest<*> -> jsonResponse(toJson(agentCardHandler.handleJsonRpc(request)))
            }
        } catch (e: A2AError) {
            errorResponse(requestIdOf(body), e)
        } catch (e: InvalidParamsJsonMappingException) {
            errorResponse(e.id, InvalidParamsError(null, e.message, null))
        } catch (e: MethodNotFoundJsonMappingException) {
            errorResponse(e.id, MethodNotFoundError(null, e.message, null))
        } catch (e: IdJsonMappingException) {
            errorResponse(e.id, InvalidRequestError(null, e.message, null))
        } catch (e: JsonMappingException) {
            errorResponse(null, InvalidRequestError(null, e.message, null))
        } catch (e: JsonProcessingException) {
            errorResponse(null, JSONParseError(e.message))
        } catch (e: JsonSyntaxException) {
            errorResponse(null, JSONParseError(e.message))
        } catch (e: Throwable) {
            // Throwable, not Exception: errors such as kotlin.NotImplementedError must still get a JSON-RPC response
            logger.error("Error handling JSON-RPC request: {}", e.message, e)
            errorResponse(requestIdOf(body), InternalError("Internal server error: ${e.message}"))
        }
    }

    /**
     * Per A2A 1.0, a request without the A2A-Version header is an A2A 0.3 request.
     * Any 1.x version is compatible.
     */
    private fun checkProtocolVersion(a2aVersion: String?) {
        val requested = a2aVersion?.takeIf { it.isNotBlank() } ?: "0.3"
        val supported = AgentInterface.CURRENT_PROTOCOL_VERSION
        if (requested.substringBefore('.') != supported.substringBefore('.')) {
            throw VersionNotSupportedError(
                null,
                "Protocol version '$requested' is not supported. Supported versions: [$supported]",
                null,
            )
        }
    }

    private fun toJson(response: A2AResponse<*>): String {
        response.error?.let { return JSONRPCUtils.toJsonRPCErrorResponse(response.id, it) }
        val result = when (response) {
            is SendMessageResponse -> ProtoUtils.ToProto.taskOrMessage(response.result)
            is GetTaskResponse -> ProtoUtils.ToProto.task(response.result)
            is CancelTaskResponse -> ProtoUtils.ToProto.task(response.result)
            else -> throw IllegalArgumentException("Unsupported response type: ${response::class.java.name}")
        }
        return JSONRPCUtils.toJsonRPCResultResponse(response.id, result)
    }

    /**
     * Best effort, so that errors raised before the request is parsed still carry its id.
     */
    private fun requestIdOf(body: String): Any? =
        runCatching { objectMapper.readValue(body, Map::class.java)["id"] }.getOrNull()

    private fun errorResponse(requestId: Any?, error: A2AError): ResponseEntity<String> {
        logger.warn("Returning JSON-RPC error: id='{}', code={}, message='{}'", requestId, error.code, error.message)
        return jsonResponse(JSONRPCUtils.toJsonRPCErrorResponse(requestId, error))
    }

    private fun jsonResponse(json: String): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
}
