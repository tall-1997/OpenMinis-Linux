package com.openminis.app.automation.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.automation.AutomationRuleEntity
import com.openminis.app.automation.AutomationRuleManager
import com.openminis.app.automation.AutomationRunEntity
import com.openminis.app.automation.RuleSpec
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-automation-rules] Backing VM for the automation tab (rule list),
 * the rule editor and the per-rule run records screen — the counterpart of
 * [com.openminis.app.ui.scheduled.ScheduledTasksViewModel] for rules.
 *
 * The same half-built-Application discipline applies here as everywhere a
 * notification tap can restore a screen: `MinisApp.database` is read behind a
 * readiness gate and a retry counter, never unconditionally.
 */
class AutomationRulesViewModel(private val appContext: Context) : ViewModel() {

    private val manager = AutomationRuleManager(appContext)

    /** Bumped by [refresh] to retry DB binding after safe-mode recovery. */
    private val retry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val rules: StateFlow<List<AutomationRuleEntity>> =
        retry.flatMapLatest {
            flow {
                val dao = manager.daoOrNull
                if (dao == null) emit(emptyList()) else dao.observeRules().collect { emit(it) }
            }.flowOn(Dispatchers.IO)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Re-bind the DB flow and defensively re-sync OS registrations — called
     * on every tab composition so alarms/monitor repair themselves even if a
     * boot broadcast was missed.
     */
    fun refresh() {
        retry.value += 1
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { manager.sync() }
                .onFailure { AppLogger.warning(TAG, "sync on refresh failed: ${it.message}") }
        }
    }

    suspend fun get(ruleId: String): AutomationRuleEntity? = withContext(Dispatchers.IO) {
        manager.get(ruleId)
    }

    suspend fun runs(ruleId: String, limit: Int = 50): List<AutomationRunEntity> =
        withContext(Dispatchers.IO) { manager.recentRuns(ruleId, limit) }

    fun setEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { manager.setEnabled(ruleId, enabled) }
    }

    fun delete(ruleId: String) {
        viewModelScope.launch(Dispatchers.IO) { manager.delete(ruleId) }
    }

    /**
     * Create or update from the editor. Returns the validation problems
     * (empty list = saved).
     */
    suspend fun save(
        ruleId: String?,
        spec: RuleSpec,
        knownTools: Set<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        if (ruleId == null) {
            val (entity, problems) = manager.createValidated(spec, knownTools)
            return@withContext if (entity != null) emptyList() else problems
        }
        val (entity, problems) = manager.updateValidated(
            ruleId = ruleId,
            name = spec.name,
            trigger = spec.trigger,
            conditions = spec.conditions,
            action = spec.action,
            allowedTools = spec.allowedTools,
            permissionMode = spec.permissionMode,
            knownTools = knownTools,
        )
        if (entity != null) emptyList() else problems
    }

    companion object {
        private const val TAG = "AutomationRulesVM"

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AutomationRulesViewModel(context.applicationContext) as T
                }
            }
    }
}
