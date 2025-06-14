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
package com.embabel.example.travel

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.using
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.api.dsl.parallelMap
import com.embabel.agent.config.models.AnthropicModels
import com.embabel.agent.config.models.OpenAiModels
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.domain.library.InternetResource
import com.embabel.agent.domain.library.InternetResources
import com.embabel.agent.prompt.Persona
import com.embabel.agent.shell.markdownToConsole
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byName
import com.embabel.common.ai.prompt.PromptContributor
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod

sealed interface TravelBrief : PromptContributor {
    val brief: String
    val dates: String

}

data class ExplorationTravelBrief(
    val areaToExplore: String,
    val stayingAt: String,
    override val brief: String,
    override val dates: String,
) : TravelBrief {

    override fun contribution(): String =
        """
        Area to explore: $areaToExplore
        Staying at: $stayingAt
        Brief: $brief
        Dates: $dates
    """.trimIndent()
}

data class JourneyTravelBrief(
    val from: String,
    val to: String,
    override val brief: String,
    override val dates: String,
) : TravelBrief {

    override fun contribution(): String =
        """
        Journey from: $from to: $to
        Dates: $dates
        Brief: $brief
    """.trimIndent()
}

data class PointOfInterest(
    val name: String,
    val description: String,
    val location: String,
)

data class ItineraryIdeas(
    val pointsOfInterest: List<PointOfInterest>,
)

data class ResearchedPointOfInterest(
    val pointOfInterest: PointOfInterest,
    val research: String,
    override val links: List<InternetResource>,
) : InternetResources

data class PointOfInterestFindings(
    val pointsOfInterest: List<ResearchedPointOfInterest>,
)

val TravelPlannerPersona = Persona(
    name = "Hermes",
    persona = "You are an expert travel planner",
    voice = "friendly and concise",
    objective = "Make a detailed travel plan meeting requirements",
)

class TravelPlan(
    val plan: String,
)

@Agent(description = "Make a detailed travel plan")
class TravelPlanner(
    val persona: Persona = TravelPlannerPersona,
    val wordCount: Int = 500,
) {

    @Action
    fun findPointsOfInterest(
        travelBrief: TravelBrief,
    ): ItineraryIdeas {
        return using(
            llm = LlmOptions(model = AnthropicModels.CLAUDE_35_HAIKU),
            toolGroups = setOf(CoreToolGroups.WEB, CoreToolGroups.MAPS),
        )
            .withPromptContributor(persona)
            .create(
                prompt = """
                Consider the following travel brief.
                ${travelBrief.contribution()}
                Find points of interest that are relevant to the travel brief.
            """.trimIndent(),
            )
    }

    @Action
    fun researchPointsOfInterest(
        travelBrief: TravelBrief,
        itineraryIdeas: ItineraryIdeas, context: OperationContext
    ): PointOfInterestFindings {
        val pr = context.promptRunner(
            llm = LlmOptions(
//                byName("ai/llama3.2"),
                byName(OpenAiModels.GPT_41_MINI)
            ),
            toolGroups = setOf(CoreToolGroups.WEB),
        )
        val poiFindings = itineraryIdeas.pointsOfInterest.parallelMap(context) { poi ->
            pr.create<ResearchedPointOfInterest>(
                prompt = """
                Research the following point of interest.
                Consider in particular interesting stories about art and culture and famous people.
                Your audience: ${travelBrief.brief}
                <point-of-interest-to-research>
                ${poi.name}
                ${poi.description}
                ${poi.location}
                </point-of-interest-to-research>
            """.trimIndent(),
            )
        }
        return PointOfInterestFindings(
            pointsOfInterest = poiFindings,
        )
    }

    @AchievesGoal(
        description = "Create a detailed travel plan based on the travel brief and itinerary ideas",
    )
    @Action
    fun createTravelPlan(
        travelBrief: TravelBrief,
        poiFindings: PointOfInterestFindings,
    ): TravelPlan {
        return using(
            LlmOptions(AnthropicModels.CLAUDE_37_SONNET),
            toolGroups = setOf(CoreToolGroups.WEB, CoreToolGroups.MAPS)
        )
            .withPromptContributor(persona)
            .create<TravelPlan>(
                prompt = """
                Given the following travel brief, create a detailed plan.
                <brief> ${travelBrief.contribution()}</brief>
                Consider the weather in your recommendations. Use mapping tools to consider distance of driving or walking.
                Write up in $wordCount words or less.
                Include links.

                Recount at least one interesting story about a famous person
                associated with an area.

                Consider the following points of interest:
                ${
                    poiFindings.pointsOfInterest.joinToString("\n") {
                        """
                    ${it.pointOfInterest.name}
                    ${it.research}
                    ${it.links.joinToString { link -> "${link.url}: ${link.summary}" }}
                """.trimIndent()
                    }
                }

                Create a markdown plan.
            """.trimIndent(),
            )
    }
}

@ShellComponent("Travel planner commands")
class TravelPlannerShell(
    private val agentPlatform: AgentPlatform,
) {
    @ShellMethod
    fun planTravel() {
        val travelBrief = JourneyTravelBrief(
            from = "Nice",
            to = "Paris",
            dates = "June 1-5 2025 arriving in Paris on June 5",
            brief = """
                The travelers are interested in history, art, food, wine
                and classical music.
                They love walking and cycling.
                They are driving in a car that has no power.
                   They would like to take back roads and see nice countryside.
            """.trimIndent(),
        )

        val ap = agentPlatform.runAgentWithInput(
            agent = agentPlatform.agents().single { it.name == "TravelPlanner" },
            input = travelBrief,
            processOptions = ProcessOptions(
                verbosity = Verbosity(
                    showPrompts = true,
                    showLlmResponses = true,
                ),
            )
        )
        val travelPlan = ap.lastResult() as TravelPlan

        println("Travel Plan: ${markdownToConsole(travelPlan.plan)}")
    }
}
