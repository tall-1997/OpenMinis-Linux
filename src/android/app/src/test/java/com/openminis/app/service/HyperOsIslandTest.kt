package com.openminis.app.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-hyperos-island] JVM tests for the pure halves of [HyperOsIsland]:
 * host detection (injected [HyperOsIsland.HostSignals]) and the
 * `miui.focus.param` JSON builder. The Android probes (Settings.System read,
 * SystemProperties reflection, canShowFocus binder call) are exercised on
 * device only.
 */
class HyperOsIslandTest {

    // ------------------------------------------------------------------
    // Host detection
    // ------------------------------------------------------------------

    private fun host(manufacturer: String, brand: String, fingerprint: String) =
        HyperOsIsland.isHyperOsHost(HyperOsIsland.HostSignals(manufacturer, brand, fingerprint))

    @Test fun hostDetectionMatchesXiaomiRedmiPoco() {
        assertTrue(host("Xiaomi", "Xiaomi", "Xiaomi/hyperos/apollo:16/..."))
        assertTrue(host("Redmi", "Redmi", "Redmi/pnx_eea/..."))
        assertTrue(host("POCO", "POCO", "POCO/marble_ru/..."))
        assertTrue(host("Xiaomi", "Xiaomi", "LineageOS/apollo/..."))
    }

    @Test fun hostDetectionRejectsNonXiaomiOems() {
        assertFalse(host("samsung", "samsung", "samsung/qssi/..."))
        assertFalse(host("Google", "google", "google/oriole/..."))
        assertFalse(host("Lenovo", "Lenovo", "Lenovo/zui/..."))
        assertFalse(host("", "", ""))
    }

    @Test fun hostDetectionIsCaseInsensitive() {
        assertTrue(host("XIAOMI", "Redmi", "POCO/whatever"))
        assertTrue(host("xiaomi", "xiaomi", "xiaomi/hyperos/..."))
    }

    // ------------------------------------------------------------------
    // Capability decision
    // ------------------------------------------------------------------

/**
 * [T-island-protocol-v1] `notification_focus_protocol` is1 on HyperOS1,
 *2 on HyperOS2 and3 on the超级岛 era; all three carry miui.focus.param.
 * Requiring3 (the old rule) meant HyperOS1/2 devices got no focus
 * notification at all - the reported "焦点通知失效".
 */
    @Test fun everyFocusProtocolCarriesTheTemplate() {
        assertTrue(HyperOsIsland.islandProtocolCapable(1, false))
        assertTrue(HyperOsIsland.islandProtocolCapable(2, false))
        assertTrue(HyperOsIsland.islandProtocolCapable(3, false))
        assertTrue(HyperOsIsland.islandProtocolCapable(4, false))
        assertTrue(HyperOsIsland.islandProtocolCapable(0, true))
        assertFalse("a device with neither signal is not an island host", HyperOsIsland.islandProtocolCapable(0, false))
    }

    // ------------------------------------------------------------------
    // miui.focus.param JSON
    // ------------------------------------------------------------------

    private fun content() = HyperOsIsland.IslandContent(
        business = "agent",
        ticker = "Minis",
        aodTitle = "Minis",
        bigTitle = "Shell",
        bigContent = "1 task running",
        baseTitle = "Shell",
        baseContent = "1 session |1 task running",
    )

    private fun v2(c: HyperOsIsland.IslandContent = content()): JSONObject =
        JSONObject(HyperOsIsland.buildFocusParamJson(c)).getJSONObject("param_v2")

    @Test fun focusParamJsonMirrorsTheOfficialTemplateShape() {
        val v2 = v2()
        assertEquals(1, v2.getInt("protocol"))
        assertEquals("agent", v2.getString("business"))
        assertEquals(true, v2.getBoolean("updatable"))
        assertEquals(true, v2.getBoolean("islandFirstFloat"))
        assertEquals(false, v2.getBoolean("enableFloat"))
        assertEquals(false, v2.getBoolean("filterWhenNoPermission"))
        assertEquals("Minis", v2.getString("ticker"))
        assertEquals("Minis", v2.getString("aodTitle"))
        assertEquals(HyperOsIsland.PIC_TICKER, v2.getString("tickerPic"))
        assertEquals(HyperOsIsland.PIC_AOD, v2.getString("aodPic"))

        val island = v2.getJSONObject("param_island")
        assertEquals(1, island.getInt("islandProperty"))
        val big = island.getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
        assertEquals(1, big.getInt("type"))
        assertEquals(1, big.getJSONObject("picInfo").getInt("type"))
        assertEquals(HyperOsIsland.PIC_BIG, big.getJSONObject("picInfo").getString("pic"))
        val text = big.getJSONObject("textInfo")
        assertEquals("Shell", text.getString("title"))
        assertEquals("1 task running", text.getString("content"))
        assertEquals(false, text.getBoolean("showHighlightColor"))
        val small = island.getJSONObject("smallIslandArea").getJSONObject("picInfo")
        assertEquals(1, small.getInt("type"))
        assertEquals(HyperOsIsland.PIC_SMALL, small.getString("pic"))

        val base = v2.getJSONObject("baseInfo")
        assertEquals("OS2 focus-notification card shape (DshGo XiaomiFocus)",2, base.getInt("type"))
        assertEquals("Shell", base.getString("title"))
        assertEquals("1 session |1 task running", base.getString("content"))
    }

/**
 * [T-island-no-hintinfo] The old payload put an `actionInfo.action` in
 * hintInfo that pointed at a `miui.focus.actions` entry. A finished run has
 * no Stop action, so the JSON referenced a key the Bundle never carried -
 * SystemUI then rendered a blank button or dropped the payload. DshGo ships
 * no hintInfo at all.
 */
    @Test fun focusParamJsonReferencesNoActionTheBundleMightNotCarry() {
        val v2 = v2()
        assertFalse("hintInfo was removed with its dangling action reference", v2.has("hintInfo"))
        assertFalse(v2.has("actions"))
        assertFalse(v2.getJSONObject("param_island").has("hintInfo"))
    }

    @Test fun focusParamJsonEscapesQuotesNewlinesAndUnicode() {
        val tricky = HyperOsIsland.IslandContent(
            business = "a\"b",
            ticker = "t\"t",
            aodTitle = "Minis",
            bigTitle = "执行\"中\"",
            bigContent = "line\nbreak",
            baseTitle = "标题",
            baseContent = "内容",
        )
        val v2 = v2(tricky)
        assertEquals("a\"b", v2.getString("business"))
        val text = v2.getJSONObject("param_island")
            .getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
            .getJSONObject("textInfo")
        assertEquals("执行\"中\"", text.getString("title"))
        assertEquals("line\nbreak", text.getString("content"))
        assertEquals("标题", v2.getJSONObject("baseInfo").getString("title"))
    }

    @Test fun focusParamJsonCarriesNoTimerOrProgressTemplates() {
        val v2 = v2()
        val island = v2.getJSONObject("param_island")
        assertFalse(v2.has("timerInfo"))
        assertFalse(v2.has("progressInfo"))
        assertFalse(island.has("timerInfo"))
        assertFalse(island.has("progressInfo"))
        assertTrue(island.has("bigIslandArea"))
        assertTrue(island.has("smallIslandArea"))
    }

/**
 * [T-island-payload-cap] HyperOS silently drops a focus payload above3072
 * UTF-8 bytes. The clamp must keep the JSON valid AND under the cap.
 */
    @Test fun anOversizedPayloadIsTrimmedUnderTheProtocolCap() {
        val huge = HyperOsIsland.IslandContent(
            business = "agent",
            ticker = "Minis",
            aodTitle = "Minis",
            bigTitle = "执行中",
            bigContent = "1 task running",
            baseTitle = "超长标题" + "标".repeat(2_000),
            baseContent = "超长内容" + "文".repeat(4_000),
        )
        val raw = HyperOsIsland.buildFocusParamJson(huge)
        assertTrue("fixture must exceed the cap", raw.toByteArray(Charsets.UTF_8).size <= HyperOsIsland.PARAM_MAX_BYTES)
        val base = JSONObject(raw).getJSONObject("param_v2").getJSONObject("baseInfo")
        assertTrue("the trimmed payload must still be valid JSON", base.getString("title").isNotEmpty())
        assertEquals(2, base.getInt("type"))
    }
}
