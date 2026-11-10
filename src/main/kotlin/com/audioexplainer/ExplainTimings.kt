package com.audioexplainer

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * **What each effort/depth/model combination has taken on this machine** — the [RunShape] the progress bar
 * expects, learned from finished runs and seeded by [RunShape.seed] until one has run.
 *
 * Learned rather than tabled because the spread is the user's to have: effort sets the thinking (measured:
 * ~14 s at low, ~150 s at xhigh), depth the plan's size, the model both, and the code being explained all three.
 * Machine-local (`RoamingType.DISABLED`): timings depend on this machine's network and account, not on a
 * preference worth syncing.
 */
@Service(Service.Level.APP)
@State(name = "AudioExplainerTimings", storages = [Storage("audioExplainerTimings.xml", roamingType = RoamingType.DISABLED)])
class ExplainTimings : PersistentStateComponent<ExplainTimings.State> {

    class State {
        /** `effort|depth|model` → [RunShape.encode]. */
        @JvmField var shapes: MutableMap<String, String> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    @Synchronized
    fun expected(effort: String, depth: String, model: String): RunShape =
        state.shapes[key(effort, depth, model)]?.let(RunShape::parse) ?: RunShape.seed(effort, depth)

    @Synchronized
    fun record(effort: String, depth: String, model: String, observed: RunShape) {
        state.shapes[key(effort, depth, model)] = expected(effort, depth, model).learn(observed).encode()
    }

    companion object {
        fun key(effort: String, depth: String, model: String) =
            "${effort.ifBlank { "inherited" }}|$depth|${model.ifBlank { "default" }}"

        fun getInstance(): ExplainTimings = service()
    }
}
