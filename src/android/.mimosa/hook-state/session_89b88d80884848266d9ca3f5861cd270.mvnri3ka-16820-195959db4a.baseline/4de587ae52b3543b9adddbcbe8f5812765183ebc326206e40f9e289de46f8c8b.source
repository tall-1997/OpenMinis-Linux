package com.openminis.app.provider.openai

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * A request body that is not necessarily JSON.
 *
 * [OpenAIProvider.rawPassthroughRequest] used to take `bodyObject: JSONObject?`
 * and hardcode `Content-Type: application/json`. That is fine for
 * chat-completions-shaped endpoints and wrong for every vendor route that
 * speaks multipart or raw bytes — `/images/edits`, `audio/transcriptions`,
 * arbitrary file uploads.
 *
 * Content-Type is DERIVED, never assumed. [Json] and [Empty] still report
 * `application/json` so existing requests stay byte-identical. [Multipart]
 * returns null so OkHttp owns the `boundary`. [Bytes] carries its own type.
 *
 * Keys stay in the provider via [OpenAIProvider.applyKeyAuth] — do not shell
 * out to `curl -F` inside PRoot.
 */
sealed interface HttpBody {

    /**
     * How many bytes this body will put on the wire, or -1 when unknown.
     * Feeds [RequestBodyGate] BEFORE anything is allocated.
     */
    val estimatedBytes: Long

    /**
     * How many copies of the payload exist at peak while the body is being
     * produced. JSON serialization holds the source object plus the old and
     * new buffers (~3x); multipart and raw bytes are already resident and
     * OkHttp streams them, so no extra copy (~1x).
     */
    val serializationMultiplier: Int

    /**
     * The Content-Type this body implies, or null when it should not be set.
     * [Multipart] deliberately returns null — OkHttp fills it in.
     */
    fun contentType(): MediaType?

    /** Build the OkHttp body, or null for [Empty] / GET. */
    fun toOkHttpRequestBody(): RequestBody?

    /** One `multipart/form-data` part. Nested so tests can write `HttpBody.Part`. */
    sealed interface Part {
        val name: String
        val estimatedBytes: Long
        fun addFormDataPart(builder: MultipartBody.Builder)

        data class Field(override val name: String, val value: String) : Part {
            override val estimatedBytes: Long
                get() = value.toByteArray(Charsets.UTF_8).size.toLong()

            override fun addFormDataPart(builder: MultipartBody.Builder) {
                builder.addFormDataPart(name, value)
            }
        }

        data class FilePart(
            override val name: String,
            val filename: String,
            val mediaType: String,
            val data: ByteArray,
        ) : Part {
            override val estimatedBytes: Long get() = data.size.toLong()

            override fun addFormDataPart(builder: MultipartBody.Builder) {
                builder.addFormDataPart(
                    name,
                    filename,
                    data.toRequestBody(mediaType.toMediaType()),
                )
            }

            override fun equals(other: Any?): Boolean =
                this === other || (
                    other is FilePart &&
                        name == other.name &&
                        filename == other.filename &&
                        mediaType == other.mediaType &&
                        data.contentEquals(other.data)
                    )

            override fun hashCode(): Int {
                var r = 31 * name.hashCode() + filename.hashCode()
                r = 31 * r + mediaType.hashCode()
                r = 31 * r + data.contentHashCode()
                return r
            }
        }
    }

    data class Json(val obj: JSONObject) : HttpBody {
        override val estimatedBytes: Long get() = obj.toString().length.toLong()
        override val serializationMultiplier: Int get() = 3
        override fun contentType(): MediaType = JSON_MEDIA_TYPE
        override fun toOkHttpRequestBody(): RequestBody {
            val bytes = obj.toString().toByteArray(Charsets.UTF_8)
            return bytes.toRequestBody(JSON_MEDIA_TYPE)
        }
    }

    /**
     * `multipart/form-data`. Parts preserve the order they are declared in —
     * servers that read `image[]` repeat a field name rely on that order.
     */
    data class Multipart(val parts: List<Part>) : HttpBody {
        override val estimatedBytes: Long
            get() = parts.sumOf { it.estimatedBytes } + OVERHEAD_BYTES
        override val serializationMultiplier: Int get() = 1
        override fun contentType(): MediaType? = null
        override fun toOkHttpRequestBody(): RequestBody {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            for (part in parts) part.addFormDataPart(builder)
            return builder.build()
        }
    }

    /** A single opaque payload with an explicit media type. */
    data class Bytes(val data: ByteArray, val mediaType: String) : HttpBody {
        override val estimatedBytes: Long get() = data.size.toLong()
        override val serializationMultiplier: Int get() = 1
        override fun contentType(): MediaType = mediaType.toMediaType()
        override fun toOkHttpRequestBody(): RequestBody =
            data.toRequestBody(mediaType.toMediaType())

        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && mediaType == other.mediaType && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * data.contentHashCode() + mediaType.hashCode()
    }

    /** No body at all (GET, or an explicit empty POST). */
    data object Empty : HttpBody {
        override val estimatedBytes: Long get() = 0L
        override val serializationMultiplier: Int get() = 1
        override fun contentType(): MediaType = JSON_MEDIA_TYPE
        override fun toOkHttpRequestBody(): RequestBody? = null
    }

    companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

        /**
         * multipart framing: boundary line + part headers + CRLFs per part.
         * Rough but deliberately generous — it only feeds a ceiling check.
         */
        private const val OVERHEAD_BYTES = 512L
    }
}
