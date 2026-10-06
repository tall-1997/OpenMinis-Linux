package com.openminis.app.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

data class PersonaPromptEntry(
    val id: String,
    val fileName: String,
    val storedName: String,
    val builtin: Boolean,
)

data class PersonaPromptIndex(
    val prompts: List<PersonaPromptEntry>,
    val selectedId: String,
    val providerSelections: Map<String, String>,
)

data class ResolvedPersonaPrompt(
    val id: String,
    val fileName: String,
    val body: String,
    /**
     * Which level of the priority chain produced this body:
     * session override > per-provider selection > global selection > builtin.
     * The session menu and any "which persona is live?" readout use it to label
     * the source instead of guessing from file names.
     */
    val scope: String = SCOPE_GLOBAL,
) {
    companion object {
        const val SCOPE_SESSION = "session"
        const val SCOPE_PROVIDER = "provider"
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_BUILTIN = "builtin"
    }
}

enum class PersonaImportError {
    EMPTY,
    TOO_LARGE,
    UNREADABLE,
    BAD_TYPE,
}

enum class PersonaImportConflictKind {
    NONE,
    SAME_CONTENT,
    SAME_NAME,
}

data class PersonaPromptFingerprint(
    val fileName: String,
    val body: String,
    val builtin: Boolean,
)

data class PersonaImportConflict(
    val kind: PersonaImportConflictKind,
    val existingName: String = "",
    val canOverwrite: Boolean = false,
)

sealed class PersonaImportPreview {
    data class Ready(
        val fileName: String,
        val body: String,
        val conflict: PersonaImportConflict,
    ) : PersonaImportPreview()

    data class Failure(val reason: PersonaImportError) : PersonaImportPreview()
}

sealed class PersonaImportResult {
    data class Success(val entry: PersonaPromptEntry) : PersonaImportResult()
    data class Failure(val reason: PersonaImportError) : PersonaImportResult()
}

object PersonaPromptLogic {
    const val BUILTIN_ID = "soul"
    const val BUILTIN_FILE_NAME = "SOUL.md"
    const val FOLLOW_DEFAULT = ""
    const val MAX_IMPORT_BYTES = 1_048_576

    fun builtinEntry(): PersonaPromptEntry = PersonaPromptEntry(
        id = BUILTIN_ID,
        fileName = BUILTIN_FILE_NAME,
        storedName = "",
        builtin = true,
    )

    fun emptyIndex(): PersonaPromptIndex = PersonaPromptIndex(
        prompts = listOf(builtinEntry()),
        selectedId = BUILTIN_ID,
        providerSelections = emptyMap(),
    )

    fun isImportableName(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower.endsWith(".md") ||
            lower.endsWith(".txt") ||
            lower.endsWith(".markdown")
    }

    fun isImportableMime(mime: String?): Boolean {
        val t = mime.orEmpty().lowercase()
        if (t.isEmpty() || t == "application/octet-stream") return false
        return t == "text/plain" ||
            t == "text/markdown" ||
            t == "text/x-markdown" ||
            t.startsWith("text/")
    }

    fun sanitizeFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.replace(Regex("[\\x00-\\x1F\\\\/:*?\"<>|]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "persona.md"
        return name.take(120)
    }

    fun uniqueDisplayName(desired: String, existing: Set<String>): String {
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }

    fun normalizePromptBody(body: String): String =
        body.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()

    /**
     * Same body (any name, including both the same) asks whether to import a
     * suffixed copy. Same name and different body asks whether to overwrite.
     * Builtin SOUL.md is never an overwrite target.
     */
    fun classifyImportConflict(
        incomingName: String,
        incomingBody: String,
        existing: List<PersonaPromptFingerprint>,
    ): PersonaImportConflict {
        val body = normalizePromptBody(incomingBody)
        val name = incomingName.trim()
        val sameBody = existing.firstOrNull { normalizePromptBody(it.body) == body }
        if (sameBody != null) {
            return PersonaImportConflict(
                kind = PersonaImportConflictKind.SAME_CONTENT,
                existingName = sameBody.fileName,
                canOverwrite = false,
            )
        }
        val sameName = existing.firstOrNull { it.fileName == name }
        if (sameName != null) {
            return PersonaImportConflict(
                kind = PersonaImportConflictKind.SAME_NAME,
                existingName = sameName.fileName,
                canOverwrite = !sameName.builtin,
            )
        }
        return PersonaImportConflict(PersonaImportConflictKind.NONE)
    }

    fun extractImportedBody(raw: String): String {
        val text = raw.removePrefix("\uFEFF")
        return SoulMDParser.parse(text).body
    }

    fun normalizeIndex(index: PersonaPromptIndex): PersonaPromptIndex {
        val seen = linkedSetOf<String>()
        val prompts = mutableListOf<PersonaPromptEntry>()
        var hasBuiltin = false
        for (p in index.prompts) {
            if (p.id.isBlank() || p.id in seen) continue
            seen += p.id
            if (p.builtin || p.id == BUILTIN_ID) {
                hasBuiltin = true
                prompts += builtinEntry()
            } else if (p.storedName.isNotBlank() && p.fileName.isNotBlank()) {
                prompts += p
            }
        }
        if (!hasBuiltin) prompts.add(0, builtinEntry())
        val ids = prompts.map { it.id }.toSet()
        val selected = if (index.selectedId in ids) index.selectedId else BUILTIN_ID
        val providers = index.providerSelections.filter { (k, v) ->
            k.isNotBlank() && v in ids
        }
        return PersonaPromptIndex(prompts, selected, providers)
    }

    fun resolveId(index: PersonaPromptIndex, providerInstanceId: String?): String =
        resolveIdWithSource(index, providerInstanceId).first

    /**
     * [resolveId] plus which level of the shared (non-session) chain won, so
     * callers can label the source. Pure on purpose: the session override sits
     * ABOVE this chain and is decided by file presence in
     * [PersonaPromptLibrary.resolve], so the full priority order is
     * session > provider > global > builtin and each level is testable here.
     */
    fun resolveIdWithSource(index: PersonaPromptIndex, providerInstanceId: String?): Pair<String, String> {
        val normalized = normalizeIndex(index)
        val ids = normalized.prompts.map { it.id }.toSet()
        val mapped = providerInstanceId
            ?.takeIf { it.isNotBlank() }
            ?.let { normalized.providerSelections[it] }
            ?.takeIf { it in ids }
        val id = when {
            mapped != null -> mapped
            normalized.selectedId in ids -> normalized.selectedId
            else -> BUILTIN_ID
        }
        // Scope describes the BODY that will be injected, so it follows the id:
        // normalizeIndex repairs a stale or missing selection onto the builtin
        // entry, and that must read as "default", not as a global selection
        // which does not exist.
        val scope = when {
            id == BUILTIN_ID -> ResolvedPersonaPrompt.SCOPE_BUILTIN
            mapped != null -> ResolvedPersonaPrompt.SCOPE_PROVIDER
            else -> ResolvedPersonaPrompt.SCOPE_GLOBAL
        }
        return id to scope
    }

    const val HISTORY_STEERING_PREFIX = "<persona-binding>"
    const val HISTORY_STEERING_SUFFIX = "</persona-binding>"

    fun historySteeringBlock(): String =
        "$HISTORY_STEERING_PREFIX\n" +
            "The Personality block in the system prompt is BINDING for this turn. " +
            "Earlier assistant replies in this conversation may predate that persona or use a generic voice — " +
            "do not continue that voice. Stay in character unless the user explicitly asks you to leave it.\n" +
            HISTORY_STEERING_SUFFIX

    fun wrapUserTextWithHistorySteering(userText: String): String {
        if (userText.contains(HISTORY_STEERING_PREFIX)) return userText
        val block = historySteeringBlock()
        return if (userText.isBlank()) block else "$block\n\n$userText"
    }

    fun isUserTextTurn(msg: LLMMessage): Boolean {
        if (msg.role != LLMMessage.Role.USER) return false
        val parts = msg.contentParts
        if (parts.any { it is AgentContentPart.ToolUse }) return false
        val hasToolResult = parts.any { it is AgentContentPart.ToolResult }
        val hasText = msg.content.isNotBlank() ||
            parts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
        if (hasToolResult && !hasText) return false
        return hasText
    }

    /**
     * Existing sessions already have assistant turns in a previous voice.
     * Prefix the latest user-text turn (request-time only; never persist)
     * so the persona sits next to the generation, not only at the top of
     * a long system prompt.
     */
    fun applyHistorySteering(history: List<LLMMessage>): List<LLMMessage> {
        if (history.none { it.role == LLMMessage.Role.ASSISTANT }) return history
        val idx = history.indexOfLast { isUserTextTurn(it) }
        if (idx < 0) return history
        val msg = history[idx]
        val already = msg.content.contains(HISTORY_STEERING_PREFIX) ||
            msg.contentParts.any { it is AgentContentPart.Text && it.text.contains(HISTORY_STEERING_PREFIX) }
        if (already) return history
        val block = historySteeringBlock()
        val newContent = wrapUserTextWithHistorySteering(msg.content)
        val newParts = if (msg.contentParts.isEmpty()) {
            emptyList()
        } else {
            var done = false
            val mapped = msg.contentParts.map { part ->
                if (!done && part is AgentContentPart.Text) {
                    done = true
                    AgentContentPart.Text(wrapUserTextWithHistorySteering(part.text))
                } else {
                    part
                }
            }
            if (done) mapped else listOf(AgentContentPart.Text(block)) + mapped
        }
        return history.toMutableList().also { it[idx] = msg.copy(content = newContent, contentParts = newParts) }
    }
}

object PersonaPromptCodec {
    fun parse(json: String): PersonaPromptIndex {
        if (json.isBlank()) return PersonaPromptLogic.emptyIndex()
        return try {
            val root = JSONObject(json)
            val prompts = mutableListOf<PersonaPromptEntry>()
            val arr = root.optJSONArray("prompts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                prompts += PersonaPromptEntry(
                    id = id,
                    fileName = o.optString("fileName").ifBlank { id },
                    storedName = o.optString("storedName"),
                    builtin = o.optBoolean("builtin") || id == PersonaPromptLogic.BUILTIN_ID,
                )
            }
            val providers = mutableMapOf<String, String>()
            val sel = root.optJSONObject("providerSelections")
            if (sel != null) {
                val keys = sel.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = sel.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) providers[k] = v
                }
            }
            PersonaPromptLogic.normalizeIndex(
                PersonaPromptIndex(
                    prompts = prompts,
                    selectedId = root.optString("selectedId"),
                    providerSelections = providers,
                ),
            )
        } catch (_: Exception) {
            PersonaPromptLogic.emptyIndex()
        }
    }

    fun serialize(index: PersonaPromptIndex): String {
        val normalized = PersonaPromptLogic.normalizeIndex(index)
        val arr = JSONArray()
        for (p in normalized.prompts) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("fileName", p.fileName)
                    .put("storedName", p.storedName)
                    .put("builtin", p.builtin),
            )
        }
        val providers = JSONObject()
        for ((k, v) in normalized.providerSelections) providers.put(k, v)
        return JSONObject()
            .put("prompts", arr)
            .put("selectedId", normalized.selectedId)
            .put("providerSelections", providers)
            .toString()
    }
}

/**
 * Imported personality prompts live under the app-private
 * `minis-global/memory/personas/` tree. The builtin [SOUL.md][SoulStore]
 * identity file stays where it is; imported `.md`/`.txt` copies never
 * leave [Context.getFilesDir].
 */
object PersonaPromptLibrary {
    private const val TAG = "PersonaPromptLibrary"
    private const val SUBDIR = "minis-global/memory/personas"
    private const val FILES_SUBDIR = "files"
    private const val INDEX_NAME = "index.json"

    private val lock = Any()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun root(context: Context): File = File(context.filesDir, SUBDIR)

    fun filesDir(context: Context): File = File(root(context), FILES_SUBDIR)

    fun indexFile(context: Context): File = File(root(context), INDEX_NAME)

    /**
     * [T-prompt-cache] Cheap dirty-check fingerprint for the persona files
     * that [resolve] can return: the index, every file in the library, and
     * the per-session override. Sums (mtime XOR size) so callers can skip
     * re-rendering the identity section without re-reading file contents.
     */
    fun resolvedFileFingerprint(context: Context, providerInstanceId: String?, sessionId: String?): String {
        var acc = 0L
        fun scan(f: File) {
            if (f.exists()) acc = acc xor f.lastModified() xor f.length()
        }
        scan(indexFile(context))
        filesDir(context).listFiles()?.forEach(::scan)
        if (sessionId != null) {
            scan(
                File(
                    com.openminis.app.sandbox.SessionWorkspace.memoryDir(context.filesDir, sessionId),
                    SESSION_PERSONA_FILE,
                ),
            )
        }
        return "$acc|$providerInstanceId"
    }

    fun loadIndex(context: Context): PersonaPromptIndex = synchronized(lock) {
        loadIndexLocked(context)
    }

    fun setSelected(context: Context, promptId: String) {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val ids = index.prompts.map { it.id }.toSet()
            val next = if (promptId in ids) promptId else PersonaPromptLogic.BUILTIN_ID
            persistLocked(context, index.copy(selectedId = next))
        }
    }

    fun setProviderPrompt(context: Context, providerInstanceId: String, promptId: String?) {
        if (providerInstanceId.isBlank()) return
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val nextMap = index.providerSelections.toMutableMap()
            val ids = index.prompts.map { it.id }.toSet()
            if (promptId.isNullOrBlank() || promptId == PersonaPromptLogic.FOLLOW_DEFAULT || promptId !in ids) {
                nextMap.remove(providerInstanceId)
            } else {
                nextMap[providerInstanceId] = promptId
            }
            persistLocked(context, index.copy(providerSelections = nextMap))
        }
    }

    fun readBody(context: Context, promptId: String): String = synchronized(lock) {
        readBodyLocked(context, promptId)
    }

    fun writeBody(context: Context, promptId: String, body: String) {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val id = if (index.prompts.any { it.id == promptId }) {
                promptId
            } else {
                PersonaPromptLogic.BUILTIN_ID
            }
            if (id == PersonaPromptLogic.BUILTIN_ID) {
                val cur = SoulStore.load(context)
                    ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
                SoulStore.save(context, cur.copy(body = body))
            } else {
                val entry = index.prompts.first { it.id == id }
                val target = File(filesDir(context), entry.storedName)
                atomicWrite(target, body)
            }
            bumpLocked()
        }
    }

    /**
     * Session-scoped persona override, stored as `PERSONA.md` in the session's
     * memory directory. Editing the persona from the session menu writes here,
     * so the change applies to this chat only and never touches the provider
     * selection or the user's global persona file.
     */
    const val SESSION_PERSONA_FILE = "PERSONA.md"
    const val SESSION_OVERRIDE_ID = "session-override"

    fun sessionPersonaFile(context: Context, sessionId: String): File =
        File(
            com.openminis.app.sandbox.SessionWorkspace.memoryDir(context.filesDir, sessionId),
            SESSION_PERSONA_FILE,
        )

    fun readSessionOverride(context: Context, sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        val file = sessionPersonaFile(context, sessionId)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * Write — or clear, when [body] is blank — the session override. Atomic
     * like every other prompt-source write: a torn read here would drop the
     * persona for exactly one turn, the failure mode this whole chain exists
     * to avoid.
     */
    fun writeSessionOverride(context: Context, sessionId: String, body: String) {
        val file = sessionPersonaFile(context, sessionId)
        file.parentFile?.mkdirs()
        if (body.isBlank()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    fun resolve(context: Context, providerInstanceId: String?, sessionId: String? = null): ResolvedPersonaPrompt {
        // The session override sits above every shared level: a persona edited
        // from the session menu must win for this chat without modifying the
        // provider selection or the global persona.
        readSessionOverride(context, sessionId)?.let { body ->
            return ResolvedPersonaPrompt(
                id = SESSION_OVERRIDE_ID,
                fileName = SESSION_PERSONA_FILE,
                body = body,
                scope = ResolvedPersonaPrompt.SCOPE_SESSION,
            )
        }
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val (id, scope) = PersonaPromptLogic.resolveIdWithSource(index, providerInstanceId)
            val entry = index.prompts.find { it.id == id } ?: PersonaPromptLogic.builtinEntry()
            return ResolvedPersonaPrompt(
                id = entry.id,
                fileName = entry.fileName,
                body = readBodyLocked(context, entry.id),
                scope = scope,
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri): PersonaImportResult {
        val resolver = context.contentResolver
        val displayRaw = queryDisplayName(context, uri)
        val mime = resolver.getType(uri)
        val sanitized = PersonaPromptLogic.sanitizeFileName(
            displayRaw.ifBlank {
                when {
                    mime == "text/markdown" || mime == "text/x-markdown" -> "persona.md"
                    else -> "persona.txt"
                }
            },
        )
        if (!PersonaPromptLogic.isImportableName(sanitized) &&
            !PersonaPromptLogic.isImportableMime(mime)
        ) {
            return PersonaImportResult.Failure(PersonaImportError.BAD_TYPE)
        }

        val stream = try {
            resolver.openInputStream(uri)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import open failed: ${t.message}")
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        if (stream == null) {
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        val bytes = try {
            stream.use { readLimited(it, PersonaPromptLogic.MAX_IMPORT_BYTES) }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import read failed: ${t.message}")
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        if (bytes == null) {
            return PersonaImportResult.Failure(PersonaImportError.TOO_LARGE)
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import decode failed: ${t.message}")
            return PersonaImportResult.Failure(PersonaImportError.UNREADABLE)
        }
        val body = PersonaPromptLogic.extractImportedBody(text)
        if (body.isBlank()) {
            return PersonaImportResult.Failure(PersonaImportError.EMPTY)
        }

        synchronized(lock) {
            val index = loadIndexLocked(context)
            val existingNames = index.prompts.map { it.fileName }.toSet()
            val fileName = PersonaPromptLogic.uniqueDisplayName(
                if (PersonaPromptLogic.isImportableName(sanitized)) sanitized else "$sanitized.md",
                existingNames,
            )
            val id = UUID.randomUUID().toString()
            val storedName = "$id.md"
            val dest = File(filesDir(context), storedName)
            atomicWrite(dest, body)
            val entry = PersonaPromptEntry(
                id = id,
                fileName = fileName,
                storedName = storedName,
                builtin = false,
            )
            persistLocked(
                context,
                index.copy(
                    prompts = index.prompts + entry,
                    selectedId = id,
                ),
            )
            return PersonaImportResult.Success(entry)
        }
    }

    fun previewImport(context: Context, uri: Uri): PersonaImportPreview {
        val read = readImport(context, uri)
        if (read is PersonaImportPreview.Failure) return read
        val ready = read as PersonaImportPreview.Ready
        val conflict = synchronized(lock) {
            PersonaPromptLogic.classifyImportConflict(
                ready.fileName,
                ready.body,
                fingerprintsLocked(context),
            )
        }
        return ready.copy(conflict = conflict)
    }

    /**
     * @param overwrite replace a private file with the same display name.
     *   Builtin SOUL.md is never overwritten; a colliding builtin name is
     *   stored as a suffixed private copy instead.
     */
    fun commitPrepared(
        context: Context,
        displayName: String,
        body: String,
        overwrite: Boolean,
    ): PersonaImportResult {
        val cleanBody = PersonaPromptLogic.extractImportedBody(body)
        if (cleanBody.isBlank()) {
            return PersonaImportResult.Failure(PersonaImportError.EMPTY)
        }
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val desired = if (PersonaPromptLogic.isImportableName(displayName)) {
                displayName
            } else {
                "$displayName.md"
            }
            if (overwrite) {
                val target = index.prompts.find { it.fileName == desired && !it.builtin }
                if (target != null) {
                    atomicWrite(File(filesDir(context), target.storedName), cleanBody)
                    persistLocked(context, index.copy(selectedId = target.id))
                    return PersonaImportResult.Success(target)
                }
            }
            val fileName = PersonaPromptLogic.uniqueDisplayName(
                desired,
                index.prompts.map { it.fileName }.toSet(),
            )
            val id = UUID.randomUUID().toString()
            val storedName = "$id.md"
            atomicWrite(File(filesDir(context), storedName), cleanBody)
            val entry = PersonaPromptEntry(
                id = id,
                fileName = fileName,
                storedName = storedName,
                builtin = false,
            )
            persistLocked(
                context,
                index.copy(prompts = index.prompts + entry, selectedId = id),
            )
            return PersonaImportResult.Success(entry)
        }
    }

    private fun fingerprintsLocked(context: Context): List<PersonaPromptFingerprint> {
        val index = loadIndexLocked(context)
        return index.prompts.map { entry ->
            PersonaPromptFingerprint(
                fileName = entry.fileName,
                body = readBodyLocked(context, entry.id),
                builtin = entry.builtin,
            )
        }
    }

    private fun readImport(context: Context, uri: Uri): PersonaImportPreview {
        val resolver = context.contentResolver
        val displayRaw = queryDisplayName(context, uri)
        val mime = resolver.getType(uri)
        val sanitized = PersonaPromptLogic.sanitizeFileName(
            displayRaw.ifBlank {
                when {
                    mime == "text/markdown" || mime == "text/x-markdown" -> "persona.md"
                    else -> "persona.txt"
                }
            },
        )
        if (!PersonaPromptLogic.isImportableName(sanitized) &&
            !PersonaPromptLogic.isImportableMime(mime)
        ) {
            return PersonaImportPreview.Failure(PersonaImportError.BAD_TYPE)
        }
        val stream = try {
            resolver.openInputStream(uri)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import open failed: ${t.message}")
            return PersonaImportPreview.Failure(PersonaImportError.UNREADABLE)
        } ?: return PersonaImportPreview.Failure(PersonaImportError.UNREADABLE)
        val bytes = try {
            stream.use { readLimited(it, PersonaPromptLogic.MAX_IMPORT_BYTES) }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import read failed: ${t.message}")
            return PersonaImportPreview.Failure(PersonaImportError.UNREADABLE)
        } ?: return PersonaImportPreview.Failure(PersonaImportError.TOO_LARGE)
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "import decode failed: ${t.message}")
            return PersonaImportPreview.Failure(PersonaImportError.UNREADABLE)
        }
        val body = PersonaPromptLogic.extractImportedBody(text)
        if (body.isBlank()) {
            return PersonaImportPreview.Failure(PersonaImportError.EMPTY)
        }
        val fileName = if (PersonaPromptLogic.isImportableName(sanitized)) sanitized else "$sanitized.md"
        return PersonaImportPreview.Ready(
            fileName = fileName,
            body = body,
            conflict = PersonaImportConflict(PersonaImportConflictKind.NONE),
        )
    }

    fun deleteImported(context: Context, promptId: String): Boolean {
        synchronized(lock) {
            val index = loadIndexLocked(context)
            val entry = index.prompts.find { it.id == promptId } ?: return false
            if (entry.builtin) return false
            val dest = File(filesDir(context), entry.storedName)
            if (dest.exists() && !dest.delete()) {
                AppLogger.warning(TAG, "failed to delete ${entry.storedName}")
            }
            val remaining = index.prompts.filterNot { it.id == promptId }
            val selected = if (index.selectedId == promptId) {
                PersonaPromptLogic.BUILTIN_ID
            } else {
                index.selectedId
            }
            val providers = index.providerSelections.filterValues { it != promptId }
            persistLocked(
                context,
                PersonaPromptIndex(remaining, selected, providers),
            )
            return true
        }
    }

    private fun loadIndexLocked(context: Context): PersonaPromptIndex {
        val file = indexFile(context)
        if (!file.isFile) {
            return PersonaPromptLogic.emptyIndex()
        }
        return try {
            PersonaPromptCodec.parse(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "index load failed: ${t.message}")
            PersonaPromptLogic.emptyIndex()
        }
    }

    private fun persistLocked(context: Context, index: PersonaPromptIndex) {
        val normalized = PersonaPromptLogic.normalizeIndex(index)
        atomicWrite(indexFile(context), PersonaPromptCodec.serialize(normalized))
        bumpLocked()
    }

    private fun bumpLocked() {
        _revision.value = _revision.value + 1
    }

    private fun readBodyLocked(context: Context, promptId: String): String {
        if (promptId == PersonaPromptLogic.BUILTIN_ID) {
            return SoulStore.load(context)?.body.orEmpty()
        }
        val index = loadIndexLocked(context)
        val entry = index.prompts.find { it.id == promptId }
        if (entry == null || entry.builtin) {
            return SoulStore.load(context)?.body.orEmpty()
        }
        val dest = File(filesDir(context), entry.storedName)
        if (!dest.isFile) return SoulStore.load(context)?.body.orEmpty()
        // Retry before falling back to the builtin body: one torn or busy read
        // must not silently swap the user's custom persona for the default.
        var last: Throwable? = null
        repeat(2) {
            try {
                return dest.readText(Charsets.UTF_8)
            } catch (t: Throwable) {
                last = t
            }
        }
        AppLogger.warning(TAG, "persona body read failed twice: ${last?.message}")
        return SoulStore.load(context)?.body.orEmpty()
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun readLimited(stream: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx).orEmpty()
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment.orEmpty()
    }
}
