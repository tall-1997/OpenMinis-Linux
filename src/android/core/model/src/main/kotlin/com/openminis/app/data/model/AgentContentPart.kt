package com.openminis.app.data.model

import org.json.JSONObject

/**
 * Structured content parts for agent loop messages.
 * Used to represent tool_use/tool_result blocks in the conversation history
 * that providers serialize into their native format.
 */
sealed class AgentContentPart {
    /** [isProcessText] is presentation metadata. Providers still receive [text] verbatim. */
    data class Text(val text: String, val isProcessText: Boolean = false) : AgentContentPart()

    class ToolUse private constructor(
        val id: String,
        val name: String,
        private val liveInput: JSONObject?,
        // Opaque Gemini 3.x thought signature replayed on the historical functionCall.
        val thoughtSignature: String?,
        val inputJsonSnapshot: String?,
    ) : AgentContentPart() {
        /** The caller still owns this mutable object; its serialization must never be cached. */
        constructor(id: String, name: String, input: JSONObject, thoughtSignature: String? = null) :
            this(id, name, input, thoughtSignature, null)

        /** Snapshot-backed calls return a detached object, including all nested arrays/objects. */
        val input: JSONObject get() = liveInput ?: JSONObject(checkNotNull(inputJsonSnapshot))

        /** Use the published string, or serialize the live object on every read. */
        val inputJson: String get() = inputJsonSnapshot ?: (liveInput?.toString() ?: "{}")

        /** Metadata-only copies keep the input source; supplying another JSONObject opts out of the snapshot. */
        fun copy(
            id: String = this.id,
            name: String = this.name,
            input: JSONObject? = null,
            thoughtSignature: String? = this.thoughtSignature,
        ): ToolUse = if (input == null) {
            ToolUse(id, name, liveInput, thoughtSignature, inputJsonSnapshot)
        } else {
            ToolUse(id, name, input, thoughtSignature)
        }

        operator fun component1(): String = id
        operator fun component2(): String = name
        operator fun component3(): JSONObject = input
        operator fun component4(): String? = thoughtSignature

        override fun equals(other: Any?): Boolean = this === other || (
            other is ToolUse && id == other.id && name == other.name && thoughtSignature == other.thoughtSignature &&
                liveInput === other.liveInput && inputJsonSnapshot == other.inputJsonSnapshot
            )

        override fun hashCode(): Int {
            var result = 31 * id.hashCode() + name.hashCode()
            result = 31 * result + (inputJsonSnapshot?.hashCode() ?: System.identityHashCode(liveInput))
            return 31 * result + thoughtSignature.hashCode()
        }

        companion object {
            /** Publish a valid JSON object string only after repair, or from an already persisted call. */
            fun fromInputJson(id: String, name: String, inputJson: String, thoughtSignature: String? = null): ToolUse =
                ToolUse(id, name, null, thoughtSignature, inputJson)
        }
    }

    data class ToolResult(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        val imageData: ByteArray? = null,
        val imageMimeType: String? = null,
        /**
         * iSH-visible linux path for [imageData], if it was persisted. Used
         * by request-level image budgeting to emit a re-fetchable text
         * placeholder when the bytes are elided.
         */
        val imageLinuxPath: String? = null,
        /** Local transcript metadata, never serialized to providers as tool output. */
        val receiptProof: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ToolResult) return false
            return id == other.id && name == other.name && content == other.content &&
                isError == other.isError && imageData.contentEquals(other.imageData) &&
                imageMimeType == other.imageMimeType && imageLinuxPath == other.imageLinuxPath &&
                receiptProof == other.receiptProof
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + isError.hashCode()
            result = 31 * result + (imageData?.contentHashCode() ?: 0)
            result = 31 * result + (imageMimeType?.hashCode() ?: 0)
            result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
            return 31 * result + receiptProof.hashCode()
        }
    }

    data class ImageData(
        val data: ByteArray,
        val mimeType: String,
        /**
         * iSH-visible linux path the bytes were originally persisted to, if
         * any. See [LLMMessage.ImagePart.linuxPath] for usage notes.
         */
        val linuxPath: String? = null,
        /**
         * [T-android-vision-group / GH#182] Provider substitutes this for the
         * pixels on the T264 no-native-vision path. See
         * [LLMMessage.ImagePart.noVisionPlaceholder]. Seeded by ChatViewModel
         * only when a Vision Group is configured; null → provider default literal.
         */
        val noVisionPlaceholder: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageData) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType &&
                linuxPath == other.linuxPath && noVisionPlaceholder == other.noVisionPlaceholder
        }

        override fun hashCode(): Int {
            var result = 31 * data.contentHashCode() + mimeType.hashCode()
            result = 31 * result + (linuxPath?.hashCode() ?: 0)
            result = 31 * result + (noVisionPlaceholder?.hashCode() ?: 0)
            return result
        }
    }
}
