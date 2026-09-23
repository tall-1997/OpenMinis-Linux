package com.openminis.app.provider.gemini

import com.openminis.app.provider.ModelListFetchIsolation
import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.network.SharedHttpClients
import okhttp3.Request
import org.json.JSONObject

object GeminiModelsApi {
    private val client = SharedHttpClients.default
    private val cache = ProviderModelsCache("gemini")

    /**
     * Fetch the Gemini model catalog. Three auth modes matching iOS
     * `GeminiModelsAPI`:
     *   - API key via `?key=<k>` query param
     *   - OAuth via `Authorization: Bearer <token>` (no key param)
     *   - Cloud Code Assist: no public list endpoint — caller passes
     *     `cloudCodeFallback=true` to short-circuit straight to the built-in
     *     `LLMModel.allGemini` list.
     *
     * The spec also requires a **403 fallback** on OAuth: when the OAuth
     * token lacks the `generative-language` scope (common for Cloud Code
     * Assist tokens) the endpoint returns 403 — we fall back to the built-in
     * list instead of surfacing an error, matching iOS.
     *
     * @param context When provided, enables the 7-day disk cache at
     *   `cacheDir/models-cache/gemini/<sha256>.json`.
     */
    suspend fun fetchModels(
        apiKey: String,
        isOAuth: Boolean = false,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        cacheScope: String = "",
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext LLMModel.allGemini

        val cacheKey = ModelListFetchIsolation.cacheKey((if (isOAuth) "oauth|" else "key|") + apiKey, cacheScope)
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val builder = Request.Builder()
        if (isOAuth) {
            builder.url(ModelListFetchIsolation.bustUrl("https://generativelanguage.googleapis.com/v1beta/models", forceRefresh, cacheScope))
            builder.header("Authorization", "Bearer $apiKey")
        } else {
            builder.url(ModelListFetchIsolation.bustUrl("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey", forceRefresh, cacheScope))
        }

        // [T-android-default-ua] brand outbound /v1beta/models request.
        builder.applyUserAgentOverride(null)
        val response = client.newCall(ModelListFetchIsolation.run { builder.noStoreIf(forceRefresh) }.build()).execute()
        val body = response.body?.string() ?: return@withContext LLMModel.allGemini

        if (!response.isSuccessful) {
            // 403 on OAuth almost always means the token lacks the
            // generative-language scope. Falling back to the built-in list
            // matches iOS and keeps Cloud Code Assist users functional.
            if (isOAuth && response.code == 403) return@withContext LLMModel.allGemini
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            return@withContext LLMModel.allGemini
        }

        val models = try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("models") ?: return@withContext LLMModel.allGemini
            val result = mutableListOf<LLMModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name").removePrefix("models/")
                val displayName = obj.optString("displayName", name)
                // Filter to chat-capable models (matching iOS).
                val supportsGen = obj.optJSONArray("supportedGenerationMethods")
                    ?.let { methods ->
                        (0 until methods.length()).any {
                            methods.getString(it).contains("generateContent")
                        }
                    } == true
                if (supportsGen) {
                    result.add(LLMModel(name, displayName, "Google"))
                }
            }
            if (result.isEmpty()) return@withContext LLMModel.allGemini
            ModelsDevApi.enrichModels(result)
        } catch (_: Exception) {
            return@withContext LLMModel.allGemini
        }

        if (context != null) cache.save(context, cacheKey, models)
        models
    }
}
