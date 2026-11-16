package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-canceled-io-classify] Contract for the loop-side classification of
 * OkHttp cancellation IOExceptions (IOException("Canceled")): a local
 * teardown artifact — the stall watchdog's cancel cascade, an HTTP/2
 * connection teardown, a fallback switch — becomes a transient stall whose
 * retry resumes the partial turn, while genuine provider IO failures keep
 * their own error path.
 */
class CanceledIoClassificationTest {

    @Test
    fun `raw cancel io classifies as a transient stall`() {
        val stall = CanceledIoClassification.asTransientStall(IOException("Canceled"), hasPartialText = false)
        assertNotNull("expected a transient stall", stall)
        assertEquals(
            "Transient error: stream was cancelled mid-run (local teardown) — retrying on a fresh connection",
            stall!!.message,
        )
        assertFalse("a pre-content teardown has no partial text to resume", stall.stalledAfterFirstEvent)
    }

    @Test
    fun `mid-turn cancel io resumes the partial turn`() {
        val stall = CanceledIoClassification.asTransientStall(IOException("Canceled"), hasPartialText = true)!!
        assertTrue(stall.stalledAfterFirstEvent)
    }

    @Test
    fun `wrapped cancel io still classifies — callbackFlow and NetworkError wrappers`() {
        // callbackFlow teardown wraps the cause: CancellationException(cause)
        val wrapped = CancellationException("Stream error")
        wrapped.initCause(IOException("Canceled"))
        assertNotNull(CanceledIoClassification.asTransientStall(wrapped, hasPartialText = false))

        // Anthropic/Gemini mapError path: NetworkError(cause)
        val net = LLMError.NetworkError(IOException("Canceled"))
        assertNotNull(CanceledIoClassification.asTransientStall(net, hasPartialText = false))
    }

    @Test
    fun `genuine io failures are not reclassified`() {
        assertNull(
            CanceledIoClassification.asTransientStall(IOException("connection reset by peer"), hasPartialText = false),
        )
        assertNull(CanceledIoClassification.asTransientStall(IOException("timeout"), hasPartialText = true))
        assertNull(CanceledIoClassification.asTransientStall(LLMError.Cancelled(), hasPartialText = false))
    }

    @Test
    fun `find walks the cause chain and matches the exact OkHttp message`() {
        assertNotNull(CanceledIoClassification.find(CancellationException("x").apply { initCause(IOException("Canceled")) }))
        assertNull("lookalike messages must not match", CanceledIoClassification.find(IOException("Canceled!")))
    }
}