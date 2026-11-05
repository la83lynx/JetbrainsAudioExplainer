package com.audioexplainer

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/** One model a provider lists: its id, and what it costs per million tokens (null when the list does not say). */
class ListedModel(
    val id: String,
    val name: String,
    val inPerM: Double?,
    val outPerM: Double?,
    val context: Int?,
) {
    /** A `:free` id, or a zero price — `openrouter/free` routes to free endpoints without the suffix. */
    val free: Boolean get() = Providers.isFree(id) || (inPerM == 0.0 && outPerM == 0.0)

    /** `z-ai/glm-5.3-flash   $0.15 / $0.50 per M · 1M ctx`. */
    fun label(): String {
        val price = when {
            free -> "free — may train on prompts"
            inPerM == null || outPerM == null || inPerM < 0 || outPerM < 0 -> "price varies"
            else -> "${money(inPerM)} / ${money(outPerM)} per M"
        }
        val ctx = context?.let { " · ${tokens(it)} ctx" }.orEmpty()
        return "$id   $price$ctx"
    }

    private fun money(v: Double) = "$" + String.format(Locale.ROOT, if (v < 0.1 && v > 0) "%.3f" else "%.2f", v)
    private fun tokens(n: Int) = if (n >= 1_000_000) "${n / 1_000_000}M" else "${n / 1000}K"
}

/**
 * **Browse a provider's models.** OpenRouter publishes its list at `GET <base>/v1/models` with no key, so
 * browsing sends nothing but the request itself. The other presets list models only with the key and on an
 * OpenAI-style path beside the Anthropic one, so they keep their typed id and the preset's suggestions.
 *
 * Only models that take `tools` are shown: Explain This is an agent run, and a model without tool use cannot
 * read a file or send its plan.
 */
object ProviderModels {
    private const val CACHE_MS = 10 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, List<ListedModel>>>()

    /** Whether [baseUrl] is one whose model list can be fetched — OpenRouter's, today. */
    fun canList(baseUrl: String): Boolean = Providers.host(baseUrl).equals("openrouter.ai", ignoreCase = true)

    fun listUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/v1/models"

    /** OpenRouter's `{"data": [...]}`: the tool-capable models, sorted by id. Pure — `EffortProbe` holds it. */
    fun parseOpenRouter(body: String): List<ListedModel> {
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val m = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = m["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val params = runCatching { m["supported_parameters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }
                .getOrNull().orEmpty()
            if ("tools" !in params) return@mapNotNull null
            val pricing = runCatching { m["pricing"]?.jsonObject }.getOrNull()
            fun perM(k: String) = pricing?.get(k)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { if (it < 0) it else it * 1e6 }
            ListedModel(
                id = id,
                name = m["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inPerM = perM("prompt"),
                outPerM = perM("completion"),
                context = runCatching { m["context_length"]?.jsonPrimitive?.intOrNull }.getOrNull(),
            )
        }.sortedBy { it.id.trimStart('~') }
    }

    /** Off the EDT. Cached for [CACHE_MS] per URL; throws on a network or parse failure. */
    fun fetch(baseUrl: String): List<ListedModel> {
        val url = listUrl(baseUrl)
        cache[url]?.let { (at, list) -> if (System.currentTimeMillis() - at < CACHE_MS) return list }
        val body = HttpRequests.request(url).accept("application/json")
            .connectTimeout(10_000).readTimeout(30_000).readString()
        val list = parseOpenRouter(body)
        cache[url] = System.currentTimeMillis() to list
        return list
    }

    /**
     * On the EDT: fetch behind a cancellable progress, then a popup you filter by typing. [onChosen] gets the id.
     */
    fun browse(anchor: JComponent, baseUrl: String, onChosen: (String) -> Unit) {
        var result: Result<List<ListedModel>>? = null
        val done = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { result = runCatching { fetch(baseUrl) } },
            "Fetching Models from ${Providers.host(baseUrl)}…", true, null, anchor,
        )
        if (!done) return
        val models = result?.getOrElse {
            Messages.showErrorDialog(anchor, "Couldn't fetch the model list: ${it.message ?: it}", "Audio Explainer")
            return
        }.orEmpty()
        if (models.isEmpty()) {
            Messages.showInfoMessage(anchor, "The provider listed no models with tool use.", "Audio Explainer")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(models)
            .setTitle("${models.size} Models with Tool Use — Type to Filter")
            .setRenderer(SimpleListCellRenderer.create("") { it.label() })
            .setNamerForFiltering { it.id + " " + it.name }
            .setVisibleRowCount(16)
            .setItemChosenCallback { onChosen(it.id) }
            .createPopup()
            .showUnderneathOf(anchor)
    }
}
