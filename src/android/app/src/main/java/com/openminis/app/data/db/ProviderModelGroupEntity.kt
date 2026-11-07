package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-row model group. memberEntryIds is stored as a serialized JSON
 * array string to preserve order (which is load-bearing for fallback
 * routing). After the legacy JSON→DB migration, every id in
 * member_entry_ids_json is the composite "{instanceId}/{modelId}"
 * shape — never the legacy random UUID.
 */
@Entity(tableName = "provider_model_groups")
data class ProviderModelGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val strategy: String,
    @ColumnInfo(name = "fallback_strategy") val fallbackStrategy: String,
    @ColumnInfo(name = "default_thinking_level") val defaultThinkingLevel: String? = null,
    @ColumnInfo(name = "context_limit_tokens") val contextLimitTokens: Int? = null,
    @ColumnInfo(name = "last_context_limit_tokens") val lastContextLimitTokens: Int? = null,
    @ColumnInfo(name = "member_entry_ids_json") val memberEntryIdsJson: String,
    // [T-routing-strategy-x-fix] Scheduling knobs for the five strategies.
    // Pre-fix builds stored them only in the JSON mirror, so a Room round-trip
    // dropped every value; additive nullable columns with safe defaults keep
    // old rows deserializing cleanly (weights absent → uniform wheel, null
    // timeout → default budget, null quota → quotaWindow exhausted-as-default
    // behaviour unchanged until the user configures a window).
    @ColumnInfo(name = "member_weights_json") val memberWeightsJson: String = "{}",
    @ColumnInfo(name = "first_token_timeout_ms") val firstTokenTimeoutMs: Int? = null,
    @ColumnInfo(name = "quota_window_remaining") val quotaWindowRemaining: Int? = null,
    @ColumnInfo(name = "quota_window_total") val quotaWindowTotal: Int? = null,
    @ColumnInfo(name = "quota_window_period_ms") val quotaWindowPeriodMs: Long? = null,
    @ColumnInfo(name = "quota_window_reset_at") val quotaWindowResetAt: Long? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
