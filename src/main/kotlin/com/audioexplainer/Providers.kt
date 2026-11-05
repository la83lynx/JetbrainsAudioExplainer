package com.audioexplainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.UUID

/**
 * **A provider profile**: another service behind the user's own Claude Code — OpenRouter, DeepSeek, a local
 * Ollama. Persisted as a nested bean in [AudioExplainerSettings.State.providers] (public no-arg ctor +
 * `@JvmField` mutables, like [EspeakProfile]).
 *
 * **There is no key field, and there must never be one**: the key lives in the IDE password safe
 * ([ProviderKeys]), keyed by [id], so `audioExplainer.xml` can be synced, shared or pasted into a bug report.
 *
 * One [model] per profile, written to every model slot Claude Code has: a headless run makes no background
 * calls (its `modelUsage` lists the main model only), so a second field would be a choice with no effect.
 */
class ProviderProfile {
    /** Stable identity — the password-safe key and [AudioExplainerSettings.State.explainProvider] point at it. */
    @JvmField var id: String = ""
    @JvmField var name: String = ""
    /** Claude Code's `ANTHROPIC_BASE_URL`: the Anthropic-style endpoint, without `/v1/messages`. */
    @JvmField var baseUrl: String = ""
    @JvmField var model: String = ""
    /** The [ProviderPreset.id] the profile was created from — only Ollama's changes behaviour (no key). */
    @JvmField var preset: String = ""

    fun copy(): ProviderProfile = ProviderProfile().also {
        it.id = id; it.name = name; it.baseUrl = baseUrl; it.model = model; it.preset = preset
    }
}

/**
 * Where a new profile starts. **A preset fills the fields once**; after that every field is the user's, because
 * model ids drift every few months and a preset that re-asserted itself would undo the user's fix.
 */
class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** The suggested model ids, the default first. Empty: the user must type one. */
    val models: List<String>,
    val needsKey: Boolean = true,
)

/**
 * **Everything about a provider run that is a pure function** — the presets, the `--settings` JSON, the
 * `api_retry` classifier, the stderr filter — so `probes/EffortProbe.java` holds it with no IDE.
 *
 * The mechanism (verified against OpenRouter, 2026-11-05): Claude Code is pointed at the provider through an `env` block
 * in a `--settings <file>`, which beats an `env` of the same key in `~/.claude/settings.json`. The prompt and
 * the skill do not change; the model behind the agent does.
 */
object Providers {
    const val PRESET_OPENROUTER = "openrouter"
    const val PRESET_OLLAMA = "ollama"
    const val PRESET_CUSTOM = "custom"

    /** What Ollama is sent as a token: it needs none, and Claude Code needs one. */
    const val OLLAMA_TOKEN = "ollama"

    /**
     * OpenRouter first: it is the one verified end to end (2026-11-05), and GLM 5.3 Flash first within it — the
     * fastest clean plan, at $0.004. No `:free` model in any preset: the free Nemotron got there only after 4
     * fix round trips, and free endpoints may train on the code they are sent.
     */
    val PRESETS = listOf(
        ProviderPreset(PRESET_OPENROUTER, "OpenRouter", "https://openrouter.ai/api",
            listOf("z-ai/glm-5.3-flash", "deepseek/deepseek-v4.1-flash")),
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/anthropic", listOf("deepseek-flash")),
        ProviderPreset("kimi", "Kimi (Moonshot)", "https://api.moonshot.ai/anthropic", listOf("kimi-k2.7-code")),
        ProviderPreset("zai", "GLM (Z.ai)", "https://api.z.ai/api/anthropic", listOf("glm-5.3-flash")),
        ProviderPreset("qwen", "Qwen (DashScope intl)", "https://dashscope-intl.aliyuncs.com/apps/anthropic", emptyList()),
        ProviderPreset(PRESET_OLLAMA, "Ollama (local)", "http://localhost:11434", emptyList(), needsKey = false),
        ProviderPreset(PRESET_CUSTOM, "Custom", "", emptyList()),
    )

    fun preset(id: String?): ProviderPreset? = PRESETS.firstOrNull { it.id == id }

    /** A fresh profile filled from [preset], with a new [ProviderProfile.id]. */
    fun newProfile(preset: ProviderPreset, name: String = preset.label): ProviderProfile = ProviderProfile().also {
        it.id = UUID.randomUUID().toString()
        it.name = name
        it.baseUrl = preset.baseUrl
        it.model = preset.models.firstOrNull() ?: ""
        it.preset = preset.id
    }

    /** The selected profile, or null for Claude Code's own setup — including an id whose profile was removed. */
    fun selected(state: AudioExplainerSettings.State): ProviderProfile? =
        state.explainProvider.takeIf { it.isNotBlank() }?.let { id -> state.providers.firstOrNull { it.id == id } }

    fun needsKey(profile: ProviderProfile): Boolean = preset(profile.preset)?.needsKey ?: true

    /** The host the user's code goes to, for the Settings warning; the raw text when it does not parse. */
    fun host(baseUrl: String): String =
        runCatching { URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl.trim()

    /** Blank when [baseUrl] is a usable http(s) URL with a host, otherwise why not. */
    fun baseUrlProblem(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return "does not parse as a URL"
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return "is not an http(s) URL"
        if (uri.host.isNullOrBlank()) return "has no host"
        return null
    }

    /** OpenRouter's free tier marks its ids `:free`; those endpoints may train on prompts. */
    fun isFree(model: String): Boolean = model.trim().endsWith(":free")

    /** The five model slots Claude Code reads. All get the profile's model: they cost nothing and cover `Task`. */
    val MODEL_VARS = listOf(
        "ANTHROPIC_MODEL",
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "CLAUDE_CODE_SUBAGENT_MODEL",
    )

    /**
     * The `--settings` file's content: `{"env": {...}}`.
     *
     * `ANTHROPIC_API_KEY` is set **empty**, so a key in the user's own environment cannot win over the token.
     * A blank [key] on a keyless preset (Ollama) sends [OLLAMA_TOKEN].
     */
    fun settingsJson(profile: ProviderProfile, key: String?): String {
        val token = key?.trim()?.takeIf { it.isNotEmpty() } ?: OLLAMA_TOKEN
        val env = LinkedHashMap<String, JsonPrimitive>()
        env["ANTHROPIC_BASE_URL"] = JsonPrimitive(profile.baseUrl.trim().trimEnd('/'))
        env["ANTHROPIC_AUTH_TOKEN"] = JsonPrimitive(token)
        env["ANTHROPIC_API_KEY"] = JsonPrimitive("")
        for (v in MODEL_VARS) env[v] = JsonPrimitive(profile.model.trim())
        return JsonObject(mapOf("env" to JsonObject(env))).toString()
    }

    /** One `system/api_retry` event: the CLI is about to retry a failed API call. */
    class Retry(val status: Int?, val attempt: Int?, val maxRetries: Int?, val error: String?) {
        /** A rejected key: the CLI would retry it 10 times with backoff to ~40 s, about 3 minutes for nothing. */
        val authRejected: Boolean get() = status == 401 || status == 403
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The [Retry] a `stream-json` line carries, or null for any other line. */
    fun retry(line: String): Retry? {
        if (!line.contains("api_retry")) return null
        val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = runCatching { obj[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
        fun int(k: String) = runCatching { obj[k]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (str("type") != "system" || str("subtype") != "api_retry") return null
        return Retry(int("error_status"), int("attempt"), int("max_retries"), str("error"))
    }

    /**
     * stderr lines every provider run carries and none of which is a failure: Claude Code saying claude.ai
     * connectors are off because another auth source is set, and that it does not know the model's id.
     */
    private val HARMLESS_STDERR = listOf("claude.ai connectors are disabled", "[claude-code:unrecognized_model]")

    fun dropHarmlessStderr(text: String): String =
        text.lineSequence().filterNot { line -> HARMLESS_STDERR.any { line.contains(it) } }.joinToString("\n").trim()
}
