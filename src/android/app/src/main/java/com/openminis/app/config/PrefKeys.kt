package com.openminis.app.config

/**
 * Cross-layer preference keys. Lives outside ui/ so non-ui layers (i18n,
 * MainActivity) can read them without importing ui.*
 * (architecture-policy forbiddenImports). Moved verbatim from
 * ui/settings/AppearanceScreen.kt.
 */
// -- Preference Keys --
const val PREF_APPEARANCE = "appearance_prefs"

// LocaleList.forLanguageTags (API 33+) and LocaleWrap.parseLocale (pre-33)
// resolve them; every value here must also appear in res/xml/locales_config.xml
// or Android will not offer the language in system per-app settings.
const val KEY_LANGUAGE = "app_language"
