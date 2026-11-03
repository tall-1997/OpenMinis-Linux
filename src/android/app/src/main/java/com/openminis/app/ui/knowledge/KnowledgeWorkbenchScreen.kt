package com.openminis.app.ui.knowledge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.KnowledgeNoteItem
import com.openminis.app.knowledge.KnowledgeNoteGraph
import com.openminis.app.knowledge.KnowledgeNoteTransfer
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * [T-m8-knowledge-workbench] Notes list with three tabs: notes (search +
 * tag filter), graph (force-directed wiki-link view) and trash (restore /
 * purge). FAB creates a new note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeWorkbenchScreen(
    viewModel: KnowledgeWorkbenchViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val trashed by viewModel.trashed.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val activeTag by viewModel.activeTag.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbar.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var purgeTarget by remember { mutableStateOf<KnowledgeNoteItem?>(null) }
    var purgeAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(snackbar) {
        snackbar?.let { key ->
            val message = when (key) {
                KnowledgeWorkbenchViewModel.SnackKey.CREATED -> context.getString(R.string.knowledge_snack_created)
                KnowledgeWorkbenchViewModel.SnackKey.TRASHED -> context.getString(R.string.knowledge_snack_trashed)
                KnowledgeWorkbenchViewModel.SnackKey.RESTORED -> context.getString(R.string.knowledge_snack_restored)
                KnowledgeWorkbenchViewModel.SnackKey.PURGED -> context.getString(R.string.knowledge_snack_purged)
                KnowledgeWorkbenchViewModel.SnackKey.PURGED_N -> context.getString(R.string.knowledge_snack_purged)
                KnowledgeWorkbenchViewModel.SnackKey.IMPORTED -> context.getString(R.string.knowledge_snack_imported)
                else -> key
            }
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSnackbar()
        }
    }

    val filtered = remember(notes, query, activeTag) {
        val q = query.trim()
        notes.filter { note ->
            if (q.isNotEmpty() && !note.searchText.contains(q, ignoreCase = true)) false
            else activeTag == null || KnowledgeNoteTransfer.parseTags(note.tagsJson).contains(activeTag)
        }
    }
    val allTags = remember(notes) {
        notes.flatMap { KnowledgeNoteTransfer.parseTags(it.tagsJson) }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        stringResource(R.string.knowledge_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (tab == 2 && trashed.isNotEmpty()) {
                        MinisTextButton(onClick = { purgeAllConfirm = true }) {
                            Text(stringResource(R.string.knowledge_purge_expired), fontSize = 14.sp)
                        }
                    }
                }
                if (tab != 2) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.knowledge_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SegmentedButton(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 3),
                    ) { Text(stringResource(R.string.knowledge_tab_notes)) }
                    SegmentedButton(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 3),
                    ) { Text(stringResource(R.string.knowledge_tab_graph)) }
                    SegmentedButton(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        shape = SegmentedButtonDefaults.itemShape(2, 3),
                    ) { Text(stringResource(R.string.knowledge_tab_trash)) }
                }
                Spacer(Modifier.height(8.dp))
                if (tab == 0 && allTags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        allTags.forEach { tag ->
                            FilterChip(
                                selected = activeTag == tag,
                                onClick = { viewModel.setTag(if (activeTag == tag) null else tag) },
                                label = { Text(tag, fontSize = 14.sp) },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        },
        floatingActionButton = {
            if (tab != 2) {
                FloatingActionButton(onClick = { onOpenNote("") }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.knowledge_note_new))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> NoteListTab(filtered, onOpenNote)
                1 -> GraphTab(notes, onOpenNote)
                else -> TrashTab(
                    trashed = trashed,
                    onRestore = viewModel::restore,
                    onPurge = { purgeTarget = it },
                )
            }
        }
    }

    purgeTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text(stringResource(R.string.knowledge_purge_confirm_title)) },
            text = { Text(note.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purge(note.id)
                    purgeTarget = null
                }) { Text(stringResource(R.string.knowledge_purge)) }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (purgeAllConfirm) {
        AlertDialog(
            onDismissRequest = { purgeAllConfirm = false },
            title = { Text(stringResource(R.string.knowledge_purge_expired_confirm_title)) },
            text = { Text(stringResource(R.string.knowledge_purge_expired_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purgeExpiredTrash()
                    purgeAllConfirm = false
                }) { Text(stringResource(R.string.knowledge_purge_expired)) }
            },
            dismissButton = {
                TextButton(onClick = { purgeAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun NoteListTab(notes: List<KnowledgeNoteItem>, onOpenNote: (String) -> Unit) {
    if (notes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.knowledge_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(notes, key = { it.id }) { note ->
            NoteRow(note, onClick = { onOpenNote(note.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun NoteRow(note: KnowledgeNoteItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(note.id) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(note.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                formatTs(note.updatedAt) + KnowledgeNoteTransfer.parseTags(note.tagsJson)
                    .takeIf { it.isNotEmpty() }?.joinToString("  ", prefix = "  ·  ").orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GraphTab(notes: List<KnowledgeNoteItem>, onOpenNote: (String) -> Unit) {
    if (notes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.knowledge_graph_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val lite = remember(notes) {
        notes.map { KnowledgeNoteGraph.NoteLite(it.id, it.title, KnowledgeNoteTransfer.parseLinks(it.linksJson)) }
    }
    val edges = remember(lite) { KnowledgeNoteGraph.edgesOf(lite) }
    var layout by remember(lite) {
        mutableStateOf(
            KnowledgeNoteGraph.forceLayout(
                lite.map { KnowledgeNoteGraph.LayoutNode(it.id, 0f, 0f) },
                edges,
                iterations = 120,
                width = 1080f,
                height = 1920f,
            ),
        )
    }
    val index = remember(layout) { layout.associateBy { it.id } }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(lite) {
                detectTapGestures { offset ->
                    val scaleX = size.width / 1080f
                    val scaleY = size.height / 1920f
                    val hit = layout.minByOrNull { node ->
                        val dx = node.x - offset.x / scaleX
                        val dy = node.y - offset.y / scaleY
                        dx * dx + dy * dy
                    }
                    if (hit != null) {
                        val hx = hit.x - offset.x / scaleX
                        val hy = hit.y - offset.y / scaleY
                        if (hx * hx + hy * hy <= 48f * 48f) onOpenNote(hit.id)
                    }
                }
            },
    ) {
        val scaleX = size.width / 1080f
        val scaleY = size.height / 1920f
        val line = Color(0xFFB9B9C3)
        edges.forEach { edge ->
            val a = index[edge.from] ?: return@forEach
            val b = index[edge.to] ?: return@forEach
            if (a.id == b.id) return@forEach
            drawLine(line, Offset(a.x * scaleX, a.y * scaleY), Offset(b.x * scaleX, b.y * scaleY), strokeWidth = 2f)
        }
        val dot = Color(0xFF0066CC)
        layout.forEach { node ->
            drawCircle(dot, radius = 14f, center = Offset(node.x * scaleX, node.y * scaleY))
        }
    }
    // tap-on-node labels are rendered as a plain list beneath the canvas so
    // long titles stay readable without a gesture library.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            stringResource(R.string.knowledge_graph_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun TrashTab(
    trashed: List<KnowledgeNoteItem>,
    onRestore: (String) -> Unit,
    onPurge: (KnowledgeNoteItem) -> Unit,
) {
    if (trashed.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.knowledge_trash_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(trashed, key = { it.id }) { note ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(note.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.knowledge_trashed_at, formatTs(note.trashedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRestore(note.id) }) {
                    Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.knowledge_restore))
                }
                IconButton(onClick = { onPurge(note) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.knowledge_purge))
                }
            }
            HorizontalDivider()
        }
    }
}

private fun formatTs(ms: Long): String =
    if (ms <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
