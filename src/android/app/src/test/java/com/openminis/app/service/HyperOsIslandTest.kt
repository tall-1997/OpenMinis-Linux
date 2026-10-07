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
        // A Xiaomi-built device with a custom AOSP ROM still reports Xiaomi
        // hardware — host detection says yes, but the protocol probe (absent
        // settings key) keeps the island off. That split is by design.
        assertTrue(host("Xiaomi", "Xiaomi", "LineageOS/apollo/..."))
    }

    @Test fun hostDetectionRejectsNonXiaomiOems() {
        assertFalse(host("samsung", "samsung", "samsung/qssi/..."))
        assertFalse(host("Google", "google", "google/oriole/..."))
        // ZUI is a forked SystemUI (SystemUiHost must keep quiet templates
        // there) but it is NOT a HyperOS island host.
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

    @Test fun islandCapabilityRequiresOs3ProtocolOrIslandProperty() {
        assertTrue(HyperOsIsland.islandProtocolCapable(3, false))
        assertTrue(HyperOsIsland.islandProtocolCapable(4, false))
        assertTrue(HyperOsIsland.islandProtocolCapable(0, true))
        // OS2 speaks 焦点通知 but has no island UI — must NOT enable the
        // island surface.
        assertFalse(HyperOsIsland.islandProtocolCapable(2, false))
        assertFalse(HyperOsIsland.islandProtocolCapable(1, false))
        assertFalse(HyperOsIsland.islandProtocolCapable(0, false))
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
        baseContent = "1 session | 1 task running",
    )

    private fun v2(c: HyperOsIsland.IslandContent = content()): JSONObject =
        JSONObject(HyperOsIsland.buildFocusParamJson(c)).getJSONObject("param_v2")

    @Test fun focusParamJsonMirrorsTheOfficialTemplateShape() {
        val v2 = v2()
        assertEquals(1, v2.getInt("protocol"))
        assertEquals("agent", v2.getString("business"))
        assertEquals(true, v2.getBoolean("updatable"))
        assertEquals(true, v2.getBoolean("islandFirstFloat"))
        // enableFloat=false: updates never auto-expand the big island.
        assertEquals(false, v2.getBoolean("enableFloat"))
        // filterWhenNoPermission=false: a missing per-app permission must
        // degrade to a normal notification row, not a filtered-out post.
        assertEquals(false, v2.getBoolean("filterWhenNoPermission"))
        assertEquals("Minis", v2.getString("ticker"))
        assertEquals("Minis", v2.getString("aodTitle"))
        // Pic keys referenced by the JSON must be the documented constants so
        // the miui.focus.pics Bundle and the JSON stay in sync.
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
        assertEquals(1, base.getInt("type"))
        assertEquals("Shell", base.getString("title"))
        assertEquals("1 session | 1 task running", base.getString("content"))

        val actions = v2.getJSONArray("actions")
        assertEquals(2, actions.length())
        assertEquals(HyperOsIsland.ACTION_STOP, actions.getJSONObject(0).getString("action"))
        assertEquals(HyperOsIsland.ACTION_INTERRUPT, actions.getJSONObject(1).getString("action"))
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
        // Forked-SystemUI discipline: the shade row stays static, and the
        // island must not opt into timer/progress templates either — those
        // are the components whose per-second updates crash OEM islands.
        val v2 = v2()
        val island = v2.getJSONObject("param_island")
        assertFalse(v2.has("timerInfo"))
        assertFalse(v2.has("progressInfo"))
        assertFalse(island.has("timerInfo"))
        assertFalse(island.has("progressInfo"))
        // Sanity: the areas we DO ship are present.
        assertTrue(island.has("bigIslandArea"))
        assertTrue(island.has("smallIslandArea"))
    }
}
