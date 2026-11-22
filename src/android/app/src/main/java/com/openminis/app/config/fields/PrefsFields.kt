package com.openminis.app.config.fields

import android.content.SharedPreferences
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.PrefsBackend
import com.openminis.app.config.SharedPrefsBackend

/**
 * Field wrappers over a key/value store. Mirrors iOS `AppStorage*Field`s.
 *
 * Everything here reads/writes through [PrefsBackend] instead of
 * `SharedPreferences` directly. That single indirection is what makes the
 * config layer testable: `InMemoryPrefsBackend` drives these classes on a
 * plain JVM, with no Android runtime.
 *
 * Each class keeps a secondary constructor taking `SharedPreferences` so
 * existing call sites (`ConfigBuiltins`, 16 construction sites) compile
 * unchanged — they get wrapped automatically.
 *
 * Shared behaviours live in [PrefsBinding] so the seven classes stay short
 * and differ only where the persisted representation actually differs.
 */

/** Read/write helper bound to one (backend, key) pair. */
internal class PrefsBinding(
    val backend: PrefsBackend,
    val key: String,
) {
    fun bool(defaultValue: Boolean): Boolean = backend.readBoolean(key, defaultValue)
    fun int(defaultValue: Int): Int = backend.readInt(key, defaultValue)
    fun long(defaultValue: Long): Long = backend.readLong(key, defaultValue)
    fun float(defaultValue: Float): Float = backend.readFloat(key, defaultValue)
    fun string(defaultValue: String?): String? = backend.readString(key, defaultValue)
    fun stringSet(defaultValue: Set<String>?): Set<String>? = backend.readStringSet(key, defaultValue)

    fun putBool(v: Boolean) = backend.write { putBoolean(key, v) }
    fun putInt(v: Int) = backend.write { putInt(key, v) }
    fun putLong(v: Long) = backend.write { putLong(key, v) }
    fun putFloat(v: Float) = backend.write { putFloat(key, v) }
    fun putString(v: String?) = backend.write { putString(key, v) }
}

private fun wrap(prefs: SharedPreferences): PrefsBackend = SharedPrefsBackend(prefs)

// ─── Boolean ────────────────────────────────────────────────────────────────

class PrefsBoolField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val defaultValue: Boolean,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, defaultValue: Boolean,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, defaultValue, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema get() = ConfigSchema.Bool
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Bool(b.bool(defaultValue))
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        b.putBool((value as ConfigValue.Bool).value)
    }
}

// ─── Int ────────────────────────────────────────────────────────────────────

class PrefsIntField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val defaultValue: Int,
    private val minValue: Int? = null,
    private val maxValue: Int? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, defaultValue: Int,
        minValue: Int? = null, maxValue: Int? = null,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, defaultValue, minValue, maxValue, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema get() = ConfigSchema.Int(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Int(b.int(defaultValue))
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        b.putInt((value as ConfigValue.Int).value)
    }
}

// ─── Long (surfaced as Int — the bridge has no Long) ────────────────────────

class PrefsLongField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val defaultValue: Long,
    private val minValue: Long? = null,
    private val maxValue: Long? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, defaultValue: Long,
        minValue: Long? = null, maxValue: Long? = null,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, defaultValue, minValue, maxValue, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Int(min = minValue?.toInt(), max = maxValue?.toInt())
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Int(b.long(defaultValue).toInt())
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        b.putLong((value as ConfigValue.Int).value.toLong())
    }
}

// ─── Double (persisted as Float — the store has no Double) ──────────────────

class PrefsDoubleField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val defaultValue: Double,
    private val minValue: Double? = null,
    private val maxValue: Double? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, defaultValue: Double,
        minValue: Double? = null, maxValue: Double? = null,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, defaultValue, minValue, maxValue, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema get() = ConfigSchema.Double(min = minValue, max = maxValue)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue =
        ConfigValue.Double(b.float(defaultValue.toFloat()).toDouble())

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val d = when (value) {
            is ConfigValue.Double -> value.value
            is ConfigValue.Int -> value.value.toDouble()
            else -> throw ConfigError.TypeMismatch("double")
        }
        b.putFloat(d.toFloat())
    }
}

// ─── String ─────────────────────────────────────────────────────────────────

class PrefsStringField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val defaultValue: String,
    private val maxLength: Int? = null,
    private val regex: String? = null,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, defaultValue: String,
        maxLength: Int? = null, regex: String? = null,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, defaultValue, maxLength, regex, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema
        get() = ConfigSchema.Str(maxLength = maxLength, regex = regex)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue = ConfigValue.Str(b.string(null) ?: defaultValue)
    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        b.putString((value as ConfigValue.Str).value)
    }
}

// ─── String enum stored as string ───────────────────────────────────────────

class PrefsEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val cases: List<String>,
    private val defaultValue: String,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, cases: List<String>, defaultValue: String,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, cases, defaultValue, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val raw = b.string(null) ?: defaultValue
        return ConfigValue.Str(if (raw in cases) raw else defaultValue)
    }

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        b.putString((value as ConfigValue.Str).value)
    }
}

// ─── String enum stored as Int index ────────────────────────────────────────

/**
 * Legacy pickers store the index (`theme_mode` = 0 System / 1 Light / 2 Dark)
 * but the agent sees clean tokens. Reads map index → case, writes case → index.
 */
class PrefsIntCodedEnumField(
    override val path: String,
    override val displayName: String,
    override val description: String,
    prefs: PrefsBackend,
    key: String,
    private val cases: List<String>,
    private val defaultIndex: Int,
    override val risk: ConfigRisk = ConfigRisk.NORMAL,
) : ConfigField {
    constructor(
        path: String, displayName: String, description: String,
        prefs: SharedPreferences, key: String, cases: List<String>, defaultIndex: Int,
        risk: ConfigRisk = ConfigRisk.NORMAL,
    ) : this(path, displayName, description, wrap(prefs), key, cases, defaultIndex, risk)

    private val b = PrefsBinding(prefs, key)
    override val valueSchema: ConfigSchema get() = ConfigSchema.StrEnum(cases)
    override val access: ConfigAccess get() = ConfigAccess.READWRITE
    override val revertable: Boolean get() = true

    override fun read(): ConfigValue {
        val idx = b.int(defaultIndex)
        val safe = if (idx in cases.indices) idx else defaultIndex
        return ConfigValue.Str(cases[safe])
    }

    override fun write(value: ConfigValue) {
        valueSchema.validate(value)
        val s = (value as ConfigValue.Str).value
        val idx = cases.indexOf(s)
        if (idx < 0) throw ConfigError.InvalidValue("must be one of: ${cases.joinToString(", ")}")
        b.putInt(idx)
    }
}
