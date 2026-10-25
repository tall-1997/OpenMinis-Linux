package com.openminis.app.sandbox

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * OpenSSL CA directory names (`HHHHHHHH.D`).
 *
 * `oldHash` is `X509_NAME_hash_old`: MD5 of the subject DER, first 4 bytes
 * little-endian. Android's cacert filenames and OpenSSL `-compat` use this.
 *
 * `newHash` is `X509_NAME_hash`: SHA-1 of the canonical subject encoding
 * (UTF-8, ASCII-lowercased, spaces collapsed, outer SEQUENCE stripped).
 * Ubuntu 24.04's OpenSSL 3 looks up this name in `/etc/ssl/certs` when
 * `SSL_CERT_FILE` is unset — which is what an unprivileged guest process
 * sees after `su` / `env -i` drops the PRoot environment.
 */
object OpenSslSubjectHash {
    fun oldHash(cert: X509Certificate): String = oldHash(cert.subjectX500Principal.encoded)

    fun newHash(cert: X509Certificate): String = newHash(cert.subjectX500Principal.encoded)

    fun oldHash(subjectDer: ByteArray): String {
        val md = MessageDigest.getInstance("MD5").digest(subjectDer)
        return leHex(md)
    }

    fun newHash(subjectDer: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1").digest(canonicalSubject(subjectDer))
        return leHex(md)
    }

    /**
     * OpenSSL `x509_name_canon` + `i2d_name_canon`: each RDN as a DER SET,
     * concatenated, with no outer Name SEQUENCE.
     */
    fun canonicalSubject(subjectDer: ByteArray): ByteArray {
        val name = DerCursor(subjectDer).read()
        require(name.tag == 0x30) { "subject is not a SEQUENCE" }
        val body = DerCursor(subjectDer, name.contentStart, name.contentEnd)
        val sets = ArrayList<ByteArray>()
        while (body.hasRemaining()) {
            val set = body.read()
            require(set.tag == 0x31) { "RDN is not a SET" }
            val avas = ArrayList<ByteArray>()
            val setBody = DerCursor(subjectDer, set.contentStart, set.contentEnd)
            while (setBody.hasRemaining()) {
                val seq = setBody.read()
                val seqBody = DerCursor(subjectDer, seq.contentStart, seq.contentEnd)
                val oid = seqBody.read()
                val value = seqBody.read()
                val oidTlv = subjectDer.copyOfRange(oid.start, oid.end)
                val valueTlv = canonicalValue(subjectDer, value)
                avas += tlv(0x30, oidTlv + valueTlv)
            }
            avas.sortWith { a, b -> compareBytes(a, b) }
            var joined = ByteArray(0)
            for (ava in avas) joined += ava
            sets += tlv(0x31, joined)
        }
        var out = ByteArray(0)
        for (set in sets) out += set
        return out
    }

    private fun canonicalValue(data: ByteArray, value: DerTlv): ByteArray {
        if (value.tag !in CANON_TAGS) return data.copyOfRange(value.start, value.end)
        val raw = data.copyOfRange(value.contentStart, value.contentEnd)
        val utf8 = when (value.tag) {
            0x1E -> decodeBmp(raw)
            0x1C -> decodeUniversal(raw)
            0x14 -> String(raw, Charsets.ISO_8859_1).toByteArray(Charsets.UTF_8)
            else -> raw
        }
        return tlv(0x0C, asciiCanon(utf8))
    }

    /** OpenSSL `asn1_string_canon`: ASCII space collapse + ASCII tolower only. */
    fun asciiCanon(utf8: ByteArray): ByteArray {
        var start = 0
        var end = utf8.size
        while (start < end && isAsciiSpace(utf8[start])) start++
        while (end > start && isAsciiSpace(utf8[end - 1])) end--
        val out = ArrayList<Byte>(end - start)
        var i = start
        while (i < end) {
            val b = utf8[i]
            if (isAsciiSpace(b)) {
                out += 0x20
                i++
                while (i < end && isAsciiSpace(utf8[i])) i++
            } else {
                out += if (b in 0x41..0x5A) (b + 0x20).toByte() else b
                i++
            }
        }
        return out.toByteArray()
    }

    private fun isAsciiSpace(b: Byte): Boolean {
        val v = b.toInt() and 0xff
        return v == 0x20 || v in 0x09..0x0d
    }

    private fun decodeBmp(raw: ByteArray): ByteArray {
        val chars = CharArray(raw.size / 2)
        var i = 0
        var c = 0
        while (i + 1 < raw.size) {
            chars[c++] = ((raw[i].toInt() and 0xff) shl 8 or (raw[i + 1].toInt() and 0xff)).toChar()
            i += 2
        }
        return String(chars, 0, c).toByteArray(Charsets.UTF_8)
    }

    private fun decodeUniversal(raw: ByteArray): ByteArray {
        if (raw.size % 4 != 0) return raw
        val cps = IntArray(raw.size / 4)
        var n = 0
        var i = 0
        while (i + 3 < raw.size) {
            cps[n++] = (raw[i].toInt() and 0xff shl 24) or
                (raw[i + 1].toInt() and 0xff shl 16) or
                (raw[i + 2].toInt() and 0xff shl 8) or
                (raw[i + 3].toInt() and 0xff)
            i += 4
        }
        return String(cps, 0, n).toByteArray(Charsets.UTF_8)
    }

    private fun leHex(md: ByteArray): String {
        val hash = (md[0].toLong() and 0xff) or
            ((md[1].toLong() and 0xff) shl 8) or
            ((md[2].toLong() and 0xff) shl 16) or
            ((md[3].toLong() and 0xff) shl 24)
        return "%08x".format(hash)
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val len = derLen(content.size)
        val out = ByteArray(1 + len.size + content.size)
        out[0] = tag.toByte()
        len.copyInto(out, 1)
        content.copyInto(out, 1 + len.size)
        return out
    }

    private fun derLen(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len <= 0xff -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private class DerCursor(val data: ByteArray, var pos: Int = 0, val limit: Int = data.size) {
        fun hasRemaining(): Boolean = pos < limit

        fun read(): DerTlv {
            val start = pos
            val tag = data[pos++].toInt() and 0xff
            var lenByte = data[pos++].toInt() and 0xff
            val len = if (lenByte and 0x80 == 0) {
                lenByte
            } else {
                val n = lenByte and 0x7f
                var v = 0
                repeat(n) { v = (v shl 8) or (data[pos++].toInt() and 0xff) }
                v
            }
            val contentStart = pos
            pos += len
            require(pos <= limit) { "DER truncated" }
            return DerTlv(tag, start, pos, contentStart, pos)
        }
    }

    private data class DerTlv(
        val tag: Int,
        val start: Int,
        val end: Int,
        val contentStart: Int,
        val contentEnd: Int,
    )

    private val CANON_TAGS = setOf(
        0x0C, // UTF8
        0x12, // Numeric
        0x13, // Printable
        0x14, // Teletex
        0x16, // IA5
        0x1A, // Visible
        0x1C, // Universal
        0x1E, // BMP
    )
}
