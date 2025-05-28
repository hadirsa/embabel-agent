/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.config.models

import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PerTokenPricingModel
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.LocalDate

/**
 * Gemini models configuration.
 * This class will only register models when the "gemini" profile is active (and not in test).
 */
@ExcludeFromJacocoGeneratedReport(reason = "Gemini configuration can't be unit tested")
@Configuration
@EnableConfigurationProperties(GeminiProperties::class)
class GeminiModels(
    private val geminiProperties: GeminiProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
    private val environment: Environment,
) {
    private val logger = LoggerFactory.getLogger(GeminiModels::class.java)

    @PostConstruct
    fun registerModels() {

        if (!environment.activeProfiles.contains(GEMINI_PROFILE)) {
            logger.info("Gemini models will not be registered as the '{}' profile is not active", GEMINI_PROFILE)
            return
        }

        if (geminiProperties.models.isEmpty()) {
            logger.warn("No Gemini models configured.")
            return
        }
        logger.info("Registering Gemini models: {}", geminiProperties.models.map { it.name })

        var connected = false
        geminiProperties.models.forEach { modelProp ->
            try {
                val beanName = "geminiModel-${modelProp.name.replace(":", "-").lowercase()}"
                val llmModel = llmOf(modelProp)
                configurableBeanFactory.registerSingleton(beanName, llmModel)
                logger.debug("Successfully registered Gemini model {} as bean {}", modelProp.name, beanName)
                connected = true
            } catch (e: Exception) {
                logger.error("Failed to register Gemini model {}: {}", modelProp.name, e.message)
            }
        }
        if (connected) {
            logger.info("Gemini connection: SUCCESS! At least one Gemini model is active and registered.")
        } else {
            logger.error("Gemini connection: FAILURE! No models could be registered.")
        }
    }

    private fun llmOf(model: GeminiModelProperties): Llm {
        return Llm(
            name = model.name,
            model = chatModelOf(model.name),
            provider = PROVIDER,
            optionsConverter = optionsConverter,
            knowledgeCutoffDate = LocalDate.parse(model.knowledgeCutoff),
            pricingModel = PerTokenPricingModel(
                usdPer1mInputTokens = model.inputPrice,
                usdPer1mOutputTokens = model.outputPrice,
            )
        )
    }

    private fun chatModelOf(model: String): ChatModel {
        return VertexAiGeminiChatModel.builder()
            .defaultOptions(
                VertexAiGeminiChatOptions.builder()
                    .model(model)
                    .temperature(0.7)
                    .build()
            )
            .build()
    }

    private val optionsConverter: OptionsConverter = { options ->
        VertexAiGeminiChatOptions.builder()
            .temperature(options.temperature)
            .build()
    }

    companion object {
        const val GEMINI_PROFILE = "gemini"
        const val PROVIDER = "Gemini"
    }
}

@ConfigurationProperties(prefix = "spring.ai.vertex.ai.gemini")
data class GeminiProperties(
    val models: List<GeminiModelProperties> = emptyList()
)

data class GeminiModelProperties(
    val name: String = "",
    val knowledgeCutoff: String = "",
    val inputPrice: Double = 0.0,
    val outputPrice: Double = 0.0
)
