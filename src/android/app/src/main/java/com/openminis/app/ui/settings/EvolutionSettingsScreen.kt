package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.evolution.EvolutionProposal
import kotlinx.coroutines.launch

@Composable
fun EvolutionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as? MinisApp
    val engine = app?.evolutionEngine
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(engine?.prefs?.isEnabled == true) }
    val emptyProposals = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<EvolutionProposal>())
    }
    val proposals by (engine?.store?.proposals ?: emptyProposals).collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.evolution_title), onBack = null) {
        SettingsSection(
            header = stringResource(R.string.evolution_section_master),
            footer = stringResource(R.string.evolution_enable_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.evolution_enable_title),
                subtitle = stringResource(R.string.evolution_enable_subtitle),
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    engine?.prefs?.isEnabled = on
                },
                showDivider = false,
            )
        }

        if (engine == null) {
            SettingsSection(header = stringResource(R.string.evolution_pending_header)) {
                Text(
                    stringResource(R.string.evolution_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@SettingsScaffold
        }

        val pending = proposals.filter {
            it.status == EvolutionProposal.Status.PENDING ||
                it.status == EvolutionProposal.Status.DEFERRED
        }
        val accepted = proposals.filter { it.status == EvolutionProposal.Status.ACCEPTED }

        SettingsSection(
            header = stringResource(R.string.evolution_pending_header),
            footer = if (pending.isEmpty()) stringResource(R.string.evolution_empty_pending) else null,
        ) {
            if (pending.isEmpty()) {
                Text(
                    stringResource(R.string.evolution_empty_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                pending.forEachIndexed { index, p ->
                    ProposalCard(
                        proposal = p,
                        showDivider = index != pending.lastIndex,
                        onAccept = { scope.launch { engine.accept(p.id) } },
                        onReject = { scope.launch { engine.reject(p.id) } },
                        onDefer = { scope.launch { engine.defer(p.id) } },
                    )
                }
            }
        }

        if (accepted.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.evolution_accepted_header)) {
                accepted.forEachIndexed { index, p ->
                    ProposalCard(
                        proposal = p,
                        showDivider = index != accepted.lastIndex,
                        onRollback = { scope.launch { engine.rollback(p.id) } },
                    )
                }
            }
        }

        val preview = engine.learned.previewFragment()
        if (!preview.isNullOrBlank()) {
            SettingsSection(header = stringResource(R.string.evolution_learned_preview)) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: EvolutionProposal,
    showDivider: Boolean,
    onAccept: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null,
    onDefer: (() -> Unit)? = null,
    onRollback: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                proposal.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                typeLabel(proposal.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                proposal.draftText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (proposal.evidence.isNotBlank()) {
                Text(
                    proposal.evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 4,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (onReject != null) {
                    TextButton(onClick = onReject) {
                        Text(stringResource(R.string.evolution_reject))
                    }
                }
                if (onDefer != null) {
                    TextButton(onClick = onDefer) {
                        Text(stringResource(R.string.evolution_defer))
                    }
                }
                if (onAccept != null) {
                    TextButton(onClick = onAccept) {
                        Text(stringResource(R.string.evolution_accept))
                    }
                }
                if (onRollback != null) {
                    TextButton(onClick = onRollback) {
                        Text(stringResource(R.string.evolution_rollback))
                    }
                }
            }
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun typeLabel(type: EvolutionProposal.Type): String = when (type) {
    EvolutionProposal.Type.LEARNED_RULE -> stringResource(R.string.evolution_type_rule)
    EvolutionProposal.Type.SKILL_PATCH -> stringResource(R.string.evolution_type_skill)
    EvolutionProposal.Type.RETRACT -> stringResource(R.string.evolution_type_retract)
}
