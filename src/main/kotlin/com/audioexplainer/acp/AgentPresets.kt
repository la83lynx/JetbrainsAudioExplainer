package com.audioexplainer.acp

import com.audioexplainer.AudioExplainerSettings
import com.audioexplainer.ProviderProfile
import com.audioexplainer.Providers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One agent process to start: its argv after the executable, its extra environment, and a model to ask the session for. */
class AgentLaunch(
    val args: List<String>,
    val env: Map<String, String>,
    /** For an agent the launch cannot point at a model: asked of the session instead ([AcpSession]). */
    val sessionModel: String? = null,
    /** Why this agent cannot run with this provider, or null. */
    val problem: String? = null,
)

/**
 * **The ACP agents Explain This knows how to start** (`Models.B.md` B2b), as a pure function of the preset, the
 * provider profile, its key and the effort — so `probes/AcpProbe.java` holds every launch with no IDE.
 *
 * **The user's own agent config is never rewritten, and `HOME` is never redirected** — either would break the
 * agent's own login. Everything a run needs is in the child's environment and argv: opencode merges
 * `OPENCODE_CONFIG_CONTENT` over the user's file, Qwen Code takes its OpenAI-compatible endpoint from the
 * environment. No provider profile means the agent's own configured model, untouched.
 *
 * Each preset also tells its agent not to try what the policy refuses (opencode's `permission` deny-list), because
 * opencode ends its turn on a refusal.
 */
object AgentPresets {
    const val CLAUDE = "claude"
    const val OPENCODE = "opencode"
    const val QWEN = "qwen"
    const val CUSTOM = "custom"

    val ACP = listOf(OPENCODE, QWEN, CUSTOM)

    fun label(id: String): String = when (id) {
        CLAUDE -> "Claude Code"
        OPENCODE -> "opencode (ACP)"
        QWEN -> "Qwen Code (ACP)"
        CUSTOM -> "Custom ACP agent"
        else -> id
    }

    /** The executable's name on PATH, for a preset. */
    fun command(id: String): String? = when (id) {
        OPENCODE -> "opencode"
        QWEN -> "qwen"
        else -> null
    }

    /**
     * What to do when the agent is not found — one text for the run's failure and for Diagnostics, so the two
     * cannot name different fixes.
     */
    fun notFound(id: String): String = when (id) {
        OPENCODE -> "Couldn't find opencode. Install it (`npm i -g opencode-ai`), or set its path in Settings → Tools → Audio Explainer → Explanation."
        QWEN -> "Couldn't find qwen. Install Qwen Code (`npm i -g @qwen-code/qwen-code`, which needs Node.js 22 or newer), or set its path in Settings → Tools → Audio Explainer → Explanation."
        else -> "The custom ACP agent's command is not set, or its executable was not found. Set it in Settings → Tools → Audio Explainer → Explanation."
    }

    /** The environment variable the key travels in for a custom provider in opencode's config. */
    const val OPENCODE_KEY_VAR = "AUDIO_EXPLAINER_PROVIDER_KEY"
    private const val OPENCODE_PROVIDER = "audio-explainer"

    fun launch(id: String, cwd: String, profile: ProviderProfile?, key: String?, effort: String, customArgs: List<String> = emptyList()): AgentLaunch =
        when (id) {
            OPENCODE -> opencode(cwd, profile, key, effort)
            QWEN -> qwen(profile, key)
            else -> AgentLaunch(customArgs, emptyMap(), sessionModel = profile?.model?.trim()?.takeIf { it.isNotEmpty() })
        }

    /**
     * `opencode acp --pure --cwd <project>`, `--pure` leaving the user's plugins out of a run they did not set up.
     * OpenRouter is a provider opencode has; another Anthropic-style profile is declared as a provider of the run's own
     * in the merged config, its key read from [OPENCODE_KEY_VAR]; Ollama through its OpenAI-compatible endpoint.
     */
    private fun opencode(cwd: String, profile: ProviderProfile?, key: String?, effort: String): AgentLaunch {
        val env = LinkedHashMap<String, String>()
        val model = profile?.model?.trim().orEmpty()
        val config = buildJsonObject {
            put("permission", buildJsonObject { put("edit", "deny"); put("bash", "deny"); put("webfetch", "deny") })
            put("autoupdate", false)
            put("share", "disabled")
            if (profile != null && model.isNotEmpty()) {
                val reasoning = openRouterEffort(effort)
                when {
                    isOpenRouter(profile) -> {
                        key?.let { env["OPENROUTER_API_KEY"] = it }
                        put("model", "openrouter/$model")
                        if (reasoning != null) put("provider", buildJsonObject {
                            put("openrouter", modelOptions(model, buildJsonObject {
                                put("reasoning", buildJsonObject { put("effort", reasoning) })
                            }))
                        })
                    }
                    else -> {
                        val ollama = profile.preset == Providers.PRESET_OLLAMA
                        key?.let { env[OPENCODE_KEY_VAR] = it }
                        put("model", "$OPENCODE_PROVIDER/$model")
                        put("provider", buildJsonObject {
                            put(OPENCODE_PROVIDER, buildJsonObject {
                                put("name", profile.name)
                                put("npm", if (ollama) "@ai-sdk/openai-compatible" else "@ai-sdk/anthropic")
                                put("options", buildJsonObject {
                                    put("baseURL", profile.baseUrl.trim().trimEnd('/') + "/v1")
                                    put("apiKey", if (key != null) "{env:$OPENCODE_KEY_VAR}" else Providers.OLLAMA_TOKEN)
                                })
                                put("models", buildJsonObject { put(model, buildJsonObject {}) })
                            })
                        })
                    }
                }
            }
        }
        env["OPENCODE_CONFIG_CONTENT"] = config.toString()
        // opencode also loads Claude Code's skills (~/.claude/skills) — another tool's, among them maybe an older
        // copy of this plugin's own skill, beside the docs the prompt attaches. Its own setup is left as it is.
        env["OPENCODE_DISABLE_CLAUDE_CODE_SKILLS"] = "1"
        return AgentLaunch(listOf("acp", "--pure", "--cwd", cwd), env)
    }

    private fun modelOptions(model: String, options: JsonObject) =
        buildJsonObject { put("models", buildJsonObject { put(model, buildJsonObject { put("options", options) }) }) }

    /** OpenRouter's `reasoning.effort` has low, medium and high; blank leaves the model's own default. */
    internal fun openRouterEffort(effort: String): String? = when (effort.trim()) {
        AudioExplainerSettings.EFFORT_LOW -> "low"
        AudioExplainerSettings.EFFORT_MEDIUM -> "medium"
        AudioExplainerSettings.EFFORT_HIGH, AudioExplainerSettings.EFFORT_XHIGH, AudioExplainerSettings.EFFORT_MAX -> "high"
        else -> null
    }

    /**
     * `qwen --acp`, pointed at an OpenAI-compatible endpoint by `--auth-type openai` and `OPENAI_*`. A profile's base
     * URL is Anthropic-style, so each preset's OpenAI-compatible twin is named here; a Custom profile has none.
     */
    private fun qwen(profile: ProviderProfile?, key: String?): AgentLaunch {
        // `default` asks before every edit and command, whatever the user's own settings say (`yolo`, `auto-edit`):
        // the policy can refuse only what the agent asks about ([UnaskedWatch]).
        val args = arrayListOf("--acp", "--approval-mode", "default")
        if (profile == null) return AgentLaunch(args, emptyMap())
        val model = profile.model.trim()
        val base = OPENAI_BASES[if (isOpenRouter(profile)) Providers.PRESET_OPENROUTER else profile.preset]
            ?: return AgentLaunch(args, emptyMap(), problem = "Qwen Code needs an OpenAI-compatible endpoint, and ${profile.name}'s is not known — use an OpenRouter, DeepSeek, Kimi, GLM, Qwen or Ollama profile, or none")
        args += listOf("--auth-type", "openai", "--model", model)
        val env = linkedMapOf("OPENAI_BASE_URL" to base, "OPENAI_MODEL" to model)
        env["OPENAI_API_KEY"] = key ?: Providers.OLLAMA_TOKEN
        return AgentLaunch(args, env)
    }

    private val OPENAI_BASES = mapOf(
        Providers.PRESET_OPENROUTER to "https://openrouter.ai/api/v1",
        "deepseek" to "https://api.deepseek.com/v1",
        "kimi" to "https://api.moonshot.ai/v1",
        "zai" to "https://api.z.ai/api/paas/v4",
        "qwen" to "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        Providers.PRESET_OLLAMA to "http://localhost:11434/v1",
    )

    private fun isOpenRouter(p: ProviderProfile) =
        p.preset == Providers.PRESET_OPENROUTER || Providers.host(p.baseUrl).endsWith("openrouter.ai")

    private val AUTH = Regex("(?i)\\bauth|sign ?in|log ?in|api[ _-]?key|unauthori[sz]ed|\\b401\\b")

    /**
     * What to do about an agent that refused for want of a login, in the plugin's terms — the agent's own say
     * "Use /auth", a command of its TUI. With no provider the agent ran on its own setup, which has none; with one,
     * the profile's key or model is what it rejected. Null when [error] is not about auth.
     */
    fun authHint(error: String?, agent: String, provider: String?): String? {
        if (error == null || !AUTH.containsMatchIn(error)) return null
        return if (provider == null) {
            "No Provider is selected, so $agent ran on its own setup, and that has no model signed in. Choose a " +
                "Provider in Settings → Tools → Audio Explainer → Explanation (or for one run in Explain This with…), " +
                "or set $agent up on its own."
        } else {
            "$agent was given $provider and refused it: check that profile's key and model in Settings → Tools → " +
                "Audio Explainer → Explanation → Manage…."
        }
    }

    /** Every value of [launch]'s environment that is a secret, for scrubbing logs. */
    fun secrets(launch: AgentLaunch): List<String> =
        launch.env.filterKeys { it.endsWith("_API_KEY") || it == OPENCODE_KEY_VAR }.values.filter { it.length >= 8 }
}
