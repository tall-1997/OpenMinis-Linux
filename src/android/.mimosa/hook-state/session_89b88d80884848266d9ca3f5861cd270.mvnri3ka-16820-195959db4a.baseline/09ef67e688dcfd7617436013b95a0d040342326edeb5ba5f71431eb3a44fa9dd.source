package com.openminis.app.service

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * [T-android-hyperos-island] Xiaomi HyperOS "小米超级岛 / 焦点通知" protocol.
 *
 * HyperOS is a forked SystemUI: [SystemUiHost.allowsLiveNotificationTemplates]
 * deliberately returns false for it, so the AOSP Android 16 promoted-ProgressStyle
 * path never engages there. But Xiaomi ships its OWN first-party island protocol
 * that any app can speak by attaching three extras to a normal notification:
 *
 *   - `miui.focus.param`  — a JSON payload describing the small island (摘要态),
 *                           big island (展开态), status-bar ticker and AOD content
 *   - `miui.focus.pics`   — a Bundle of `Icon` parcelables the JSON references by key
 *   - `miui.focus.actions`— a Bundle of `Notification.Action` parcelables the JSON
 *                           references by key (island buttons)
 *
 * Source: dev.mi.com 开发指南 (pId=2131) + 《小米超级岛模板库》 (2026-01-29).
 *
 * Capability probing (all official, from the same guide):
 *   - `Settings.System.getInt(resolver, "notification_focus_protocol", 0)` →
 *     1 = OS1 focus-notification template, 2 = OS2, 3 = OS3 超级岛. Only OS3
 *     renders an island; the value never changes without an OS upgrade.
 *   - `SystemProperties.getBoolean("persist.sys.feature.island", false)` via
 *     reflection — device-level island support.
 *   - `content://miui.statusbar.notification.public` `canShowFocus` call —
 *     per-app focus-notification permission. This is a BINDER call to SystemUI
 *     (the guide marks it 耗时操作), so it must never run on the main thread;
 *     callers use [refreshFocusPermission] on a background executor and read
 *     the cached [focusPermissionCached] from any thread.
 *
 * Everything that builds the JSON is a pure function over [IslandContent] so it
 * is JVM-unit-testable; only the probes and [attachFocusExtras] touch Android.
 *
 * Degradation contract: the JSON sets `filterWhenNoPermission=false`, so on a
 * device where the permission is missing the notification still posts as a
 * normal row — the island just doesn't render. No crash, no filtered row.
 */
internal object HyperOsIsland {

    private const val TAG = "HyperOsIsland"

    // ---- Notification extras keys (official protocol) ----
    const val EXTRA_FOCUS_PARAM = "miui.focus.param"
    const val EXTRA_FOCUS_PICS = "miui.focus.pics"
    const val EXTRA_FOCUS_ACTIONS = "miui.focus.actions"

    // ---- Pic keys referenced by the JSON, backed by miui.focus.pics entries ----
    // 模板库 rule: every icon the JSON references must be a COLOR content
    // icon (square, >=88*88px). A status-bar small icon is a white alpha
    // mask — on the island it renders as an invisible white blob, which is
    // exactly the "应用图标没有" symptom. Callers pass the launcher icon.
    const val PIC_SMALL = "miui.focus.pic_small"
    const val PIC_BIG = "miui.focus.pic_big"
    const val PIC_TICKER = "miui.focus.pic_ticker"
    const val PIC_AOD = "miui.focus.pic_aod"

    // ---- Island action key, backed by a miui.focus.actions entry ----
    // hintInfo carries ONE actionInfo (按钮组件3), so the island gets one
    // button; the shade row keeps its own two addAction buttons.
    const val ACTION_STOP = "miui.focus.action_stop"

    /**
     * Build signals for [isHyperOsHost]. Split out so JVM tests can inject
     * arbitrary manufacturer/brand/fingerprint combos.
     */
    data class HostSignals(
        val manufacturer: String,
        val brand: String,
        val fingerprint: String,
    )

    /**
     * True on Xiaomi / Redmi / POCO hardware. This is a HOST check only — a
     * Xiaomi device running a custom AOSP ROM still returns false from
     * [islandProtocolCapable] because the HyperOS settings key is absent, so
     * host detection alone never enables the island.
     */
    fun isHyperOsHost(
        signals: HostSignals = currentHostSignals(),
    ): Boolean {
        val blob = listOf(signals.manufacturer, signals.brand, signals.fingerprint)
            .joinToString(" ")
            .lowercase()
        return blob.contains("xiaomi") || blob.contains("redmi") || blob.contains("poco")
    }

    private fun currentHostSignals(): HostSignals = HostSignals(
        manufacturer = android.os.Build.MANUFACTURER ?: "",
        brand = android.os.Build.BRAND ?: "",
        fingerprint = android.os.Build.FINGERPRINT ?: "",
    )

    // ------------------------------------------------------------------
    // Capability probes
    // ------------------------------------------------------------------

    /**
     * Pure decision over the two probe values: the OS speaks the OS3 island
     * protocol, or the device advertises island support via the system
     * property. Injected values keep this JVM-testable.
     */
    fun islandProtocolCapable(focusProtocolVersion: Int, islandSystemProperty: Boolean): Boolean =
        focusProtocolVersion >= 3 || islandSystemProperty

    /**
     * `notification_focus_protocol` from Settings.System: 1/2/3 on HyperOS
     * (OS1/OS2/OS3), 0 everywhere else. A plain settings read — cheap, no
     * permission. Cached per process because the value only changes with an
     * OS upgrade, which implies a process restart anyway.
     */
    @Volatile
    private var cachedFocusProtocolVersion: Int? = null

    fun focusProtocolVersion(context: Context): Int {
        cachedFocusProtocolVersion?.let { return it }
        val value = try {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        } catch (t: Throwable) {
            // Non-HyperOS ROMs may still throw on unknown keys in rare builds;
            // 0 = "no focus notification" is the correct degradation.
            Log.w(TAG, "notification_focus_protocol read failed: ${t.message}")
            0
        }
        cachedFocusProtocolVersion = value
        return value
    }

    /**
     * `persist.sys.feature.island` via the SystemProperties reflection the
     * official guide prescribes. Read-only property access needs no
     * permission. Cached per process for the same reason as the protocol
     * version.
     */
    @Volatile
    private var cachedIslandSystemProperty: Boolean? = null

    fun islandSystemProperty(): Boolean {
        cachedIslandSystemProperty?.let { return it }
        val value = try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(null, "persist.sys.feature.island", false) as? Boolean ?: false
        } catch (t: Throwable) {
            false
        }
        cachedIslandSystemProperty = value
        return value
    }

    /**
     * Combined capability probe for the HyperOS branch of
     * [DynamicIslandSupport.isDynamicIslandCapable].
     */
    fun isIslandCapable(context: Context): Boolean =
        islandProtocolCapable(focusProtocolVersion(context), islandSystemProperty())

    // ------------------------------------------------------------------
    // Focus-notification permission (per-app, user-togglable in system settings)
    // ------------------------------------------------------------------

    @Volatile
    private var focusPermission: Boolean? = null

    @Volatile
    private var focusPermissionProbedAtMs: Long = 0L

    /**
     * Cached per-app focus-notification permission. `false` until the first
     * [refreshFocusPermission] completes — conservative, so the floating
     * overlay keeps working until we *know* the island can render.
     */
    fun focusPermissionCached(): Boolean = focusPermission == true

    /**
     * Probe `content://miui.statusbar.notification.public` `canShowFocus`.
     * MUST be called off the main thread (binder call to SystemUI).
     * Throttled: a probe younger than [minIntervalMs] returns the cached
     * value without touching the provider.
     *
     * @return true when the value changed and callers should re-evaluate the
     *         island/overlay mutual exclusion.
     */
    @Synchronized
    fun refreshFocusPermission(context: Context, minIntervalMs: Long = 30_000L): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = focusPermission
        if (cached != null && now - focusPermissionProbedAtMs < minIntervalMs) return false
        val probed = probeFocusPermission(context)
        focusPermission = probed
        focusPermissionProbedAtMs = now
        Log.d(TAG, "canShowFocus=$probed (was=$cached)")
        return probed != cached
    }

    private fun probeFocusPermission(context: Context): Boolean = try {
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = Bundle().apply { putString("package", context.packageName) }
        val reply = context.contentResolver.call(uri, "canShowFocus", null, extras)
        reply?.getBoolean("canShowFocus", false) == true
    } catch (t: Throwable) {
        // Non-HyperOS SystemUI has no such provider — that's "no permission".
        false
    }

    // ------------------------------------------------------------------
    // JSON payload (pure, JVM-testable)
    // ------------------------------------------------------------------

    /**
     * Everything the island JSON needs. Keep every string SHORT: 模板库
     * guidance is ≤4 CJK chars for big-island text; longer text degrades to
     * small font then clips with no scrolling.
     */
    data class IslandContent(
        /** Scenario tag for Xiaomi's stats, e.g. "agent". */
        val business: String,
        /** Status-bar ticker text (OS2 焦点通知 / status bar). */
        val ticker: String,
        /** Always-on-display title. */
        val aodTitle: String,
        /** Big island (展开态) main text — the large word. */
        val bigTitle: String,
        /** Big island trailing small text. */
        val bigContent: String,
        /** OS2 focus-notification (baseInfo) main text. */
        val baseTitle: String,
        /** OS2 focus-notification supplementary text. */
        val baseContent: String,
        /** hintInfo (按钮组件3) title — the short status line above the button. */
        val hintTitle: String,
        /** Same notification id + updatable=true is the official update path. */
        val updatable: Boolean = true,
        /** First appearance renders expanded (大岛); updates stay collapsed. */
        val islandFirstFloat: Boolean = true,
    )

    /**
     * Build the `miui.focus.param` JSON. Structure mirrors the official
     * 模版接入示例 exactly (smallIslandArea carries only picInfo — that is
     * the shape the guide itself ships; adding unverified fields risks a
     * strict parser dropping the whole payload).
     *
     * Island button lives in `hintInfo` (按钮组件3, param_v2 root, next to
     * baseInfo — that is where the official sample puts it). Its
     * actionInfo.action references a `miui.focus.actions` Bundle entry by
     * key (方式一: the system renders that Action's Icon + Title). There is
     * NO root-level `actions` array in the protocol — that field belongs to
     * the progress component, and shipping it at the root renders stray
     * blank buttons on the big island.
     *
     * Deliberately absent: timerInfo / progressInfo. HyperOS's own island
     * pipeline handles those, but the shade row must stay static — the same
     * forked-SystemUI re-inflation discipline [SystemUiHost] exists for.
     */
    fun buildFocusParamJson(content: IslandContent): String {
        val bigIslandArea = JSONObject().put(
            "imageTextInfoLeft",
            JSONObject()
                .put("type", 1)
                .put(
                    "picInfo",
                    JSONObject().put("type", 1).put("pic", PIC_BIG),
                )
                .put(
                    "textInfo",
                    JSONObject()
                        .put("title", content.bigTitle)
                        .put("content", content.bigContent)
                        .put("showHighlightColor", false),
                ),
        )
        val smallIslandArea = JSONObject().put(
            "picInfo",
            JSONObject().put("type", 1).put("pic", PIC_SMALL),
        )
        val paramIsland = JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", 3600)
            .put("bigIslandArea", bigIslandArea)
            .put("smallIslandArea", smallIslandArea)
        val baseInfo = JSONObject()
            .put("type", 1)
            .put("title", content.baseTitle)
            .put("content", content.baseContent)
        val hintInfo = JSONObject()
            .put("type", 1)
            .put("title", content.hintTitle)
            .put(
                "actionInfo",
                JSONObject().put("action", ACTION_STOP),
            )
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", content.business)
            .put("islandFirstFloat", content.islandFirstFloat)
            .put("enableFloat", false)
            .put("updatable", content.updatable)
            .put("timeout", 720)
            .put("filterWhenNoPermission", false)
            .put("ticker", content.ticker)
            .put("tickerPic", PIC_TICKER)
            .put("aodTitle", content.aodTitle)
            .put("aodPic", PIC_AOD)
            .put("param_island", paramIsland)
            .put("baseInfo", baseInfo)
            .put("hintInfo", hintInfo)
        return JSONObject().put("param_v2", paramV2).toString()
    }

    // ------------------------------------------------------------------
    // Extras attachment (Android side)
    // ------------------------------------------------------------------

    /**
     * Attach the full island payload to a native [Notification.Builder].
     * `builder.addExtras` merges everything into the built notification's
     * extras, which is where SystemUI's focus-notification service reads it —
     * equivalent to the guide's post-build `notification.extras.putString`.
     *
     * @param json from [buildFocusParamJson]
     * @param icons pic-key → Icon; keys not referenced by the JSON are ignored.
     *        MUST be color content icons (launcher-grade), never white
     *        alpha-mask status-bar icons — those render invisible on the
     *        island.
     * @param islandActions action-key → Notification.Action; the Action's
     *        Icon is what the island button renders (方式一), so it must be a
     *        color icon too.
     */
    fun attachFocusExtras(
        builder: Notification.Builder,
        json: String,
        icons: Map<String, android.graphics.drawable.Icon>,
        islandActions: Map<String, Notification.Action>,
    ) {
        val bundle = Bundle()
        bundle.putString(EXTRA_FOCUS_PARAM, json)
        if (icons.isNotEmpty()) {
            val pics = Bundle()
            icons.forEach { (key, icon) -> pics.putParcelable(key, icon) }
            bundle.putBundle(EXTRA_FOCUS_PICS, pics)
        }
        if (islandActions.isNotEmpty()) {
            val actions = Bundle()
            islandActions.forEach { (key, action) -> actions.putParcelable(key, action) }
            bundle.putBundle(EXTRA_FOCUS_ACTIONS, actions)
        }
        builder.addExtras(bundle)
    }
}
