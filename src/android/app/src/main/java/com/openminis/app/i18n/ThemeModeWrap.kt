package com.openminis.app.i18n

import android.content.Context
import android.content.res.Configuration
import com.openminis.app.config.PREF_APPEARANCE

/**
 * [T-xml-first-frame-theme] values(-night)/themes.xml (and any other
 * -night-qualified resource) resolves against the SYSTEM uiMode only — the
 * in-app theme_mode pref does not flow into the first frame a cold-start
 * activity inflates from XML, so an in-app dark theme on a light system (or
 * vice versa) flashes the wrong surface color until the Compose content
 * takes over. Overriding uiMode here makes the XML theme resolution follow
 * the in-app theme: 0 = follow system (no override), 1 = light, 2 = dark.
 *
 * Must run AFTER Application.onCreate (Prefs is installed there) —
 * Activity.attachBaseContext satisfies that premise. The pref read is
 * wrapped in runCatching because attachBaseContext runs before this
 * activity's own error handling exists: a missing install (safe-mode
 * early return) or any backend failure must degrade to "no override",
 * never crash the launch path.
 *
 * Order with [LocaleWrap]: this wraps OUTSIDE the locale wrap, so the
 * new configuration is cloned from the already locale-overridden base
 * and the locale fix survives. LocaleWrap is a no-op on API 33+;
 * ThemeModeWrap applies on every API level.
 */
object ThemeModeWrap {

    /**
     * [T-i18n-no-ui-import] The canonical `KEY_THEME_MODE` lives in
     * `ui.settings`, but the architecture policy forbids non-UI layers from
     * importing `ui`. Mirror the literal value here; `ThemeModeKeyParityTest`
     * pins it against the UI constant so the two cannot drift apart.
     */
    internal const val THEME_MODE_KEY = "theme_mode"

    fun wrap(base: Context): Context {
        val mode = runCatching {
            com.openminis.app.config.Prefs.file(PREF_APPEARANCE)
                .int(THEME_MODE_KEY, 0)
        }.getOrDefault(0)
        if (mode == 0) return base
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(config)
    }
}
