package com.openminis.app.service

import org.json.JSONObject

/**
 * [T-island-payload-cap] HyperOS drops a `miui.focus.param` payload larger
 * than [HyperOsIsland.PARAM_MAX_BYTES] UTF-8 bytes.
 *
 * DshGo's XiaomiFocus halves the two long strings until the JSON fits; a
 * dropped payload is exactly the "the island never appears" symptom users
 * report, and it fails silently — nothing is logged by SystemUI.
 *
 * Kept as a top-level function (not a member) so the object's JSON builder
 * stays a pure, one-screen function.
 */
internal fun clampToProtocolLimit(json: String): String {
 if (json.toByteArray(Charsets.UTF_8).size <= HyperOsIsland.PARAM_MAX_BYTES) return json
 var out = json
 var base = JSONObject(out).getJSONObject("param_v2")
 var title = base.getJSONObject("baseInfo").getString("title")
 var content = base.getJSONObject("baseInfo").getString("content")
 while (out.toByteArray(Charsets.UTF_8).size > HyperOsIsland.PARAM_MAX_BYTES &&
 (content.length >8 || title.length >8)
 ) {
 if (content.length >8) content = content.substring(0, content.length /2)
 else title = title.substring(0, title.length /2)
 out = JSONObject(out).apply {
 getJSONObject("param_v2").put(
 "baseInfo",
 JSONObject().put("type",2).put("title", title).put("content", content),
 )
 }.toString()
 base = JSONObject(out).getJSONObject("param_v2")
 }
 return out
}
