package com.openminis.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.AskUserQuestion
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskUserQuestionsCard(
    questions: List<AskUserQuestion.Question>,
    onSubmit: (List<List<String>>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(questions) {
        MutableList(questions.size) { mutableStateListOf<String>() }
    }
    val otherText = remember(questions) {
        MutableList(questions.size) { mutableStateOf("") }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ask_user_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(12.dp))
            // Scrollable question area, height-capped so the submit/skip buttons
            // below stay pinned and reachable even with long questions/options.
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
            questions.forEachIndexed { i, q ->
                val header = q.header.ifBlank { q.question }
                Text(header, style = MaterialTheme.typography.labelLarge)
                if (q.header.isNotBlank() && q.header != q.question) {
                    Text(
                        q.question,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    q.options.forEach { opt ->
                        val on = opt.label in selected[i]
                        FilterChip(
                            selected = on,
                            onClick = {
                                if (q.multiSelect) {
                                    if (on) selected[i].remove(opt.label) else selected[i].add(opt.label)
                                } else {
                                    selected[i].clear()
                                    selected[i].add(opt.label)
                                }
                            },
                            label = { Text(opt.label, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = on,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                val desc = q.options.firstOrNull { it.label in selected[i] && it.description.isNotBlank() }
                desc?.let {
                    Text(
                        it.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = otherText[i].value,
                    onValueChange = { otherText[i].value = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.ask_user_other)) },
                )
                if (i < questions.lastIndex) Spacer(Modifier.height(16.dp))
            }
            }
            Spacer(Modifier.height(16.dp))
            MinisButton(
                onClick = {
                    val answers = questions.mapIndexed { i, _ ->
                        val picked = selected[i].toList().toMutableList()
                        val extra = otherText[i].value.trim()
                        if (extra.isNotEmpty()) picked.add(extra)
                        picked
                    }
                    onSubmit(answers)
                },
                enabled = questions.indices.all { i ->
                    selected[i].isNotEmpty() || otherText[i].value.trim().isNotEmpty()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ask_user_submit)) }
            MinisOutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.ask_user_skip)) }
        }
    }
}
