package com.openminis.app.sandbox

/**
 * [T-p1-3-shell-auth-frames] POSIX `cksum` 的纯 Kotlin 实现（逐字节查表 + 长度
 * 后缀进 CRC）。
 *
 * 为什么是 cksum 而不是 sha256：持久 shell 的会话认证标签在**guest 侧**也要
 * 计算（wrapper 每条命令用 `cksum` 从 nonce + 会话秘密派生帧标签），POSIX 保证
 * cksum 在任何 rootfs 里都在（coreutils/busybox 皆带）；宿主侧用同一个算法对
 * 上，双方零外部依赖、零版本漂移。标签对抗的是"竞争读取 stdin 的杂散进程/
 * 陈旧字节伪造帧"——CRC32 的抗冲突强度对这个威胁模型绰绰有余（这是认证不是
 * 签名：能执行任意命令的进程本来就能读写一切 app 数据）。
 *
 * 向量实测于 GNU coreutils 9.x（Git Bash）：`printf '%s' <bytes> | cksum`。
 */
internal object PosixCksum {
    private const val POLY = 0x04C11DB7

    private val TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var crc = i shl 24
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor POLY else crc shl 1
            }
            table[i] = crc
        }
    }

    /** 与 `cksum` 输出的第一个字段（无符号十进制 CRC）逐字节一致。 */
    fun cksum(bytes: ByteArray): Long {
        var crc = 0
        for (b in bytes) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        }
        // POSIX：长度按小端逐字节进 CRC（>4 字节的长度走扩展循环，天然覆盖）。
        var len = bytes.size.toLong()
        while (len != 0L) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (len and 0xFF).toInt()) and 0xFF]
            len = len ushr 8
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }

    /** 便捷入口：与 shell 侧 `printf '%s' <s> | cksum` 逐字节一致。 */
    fun cksum(s: String): Long = cksum(s.toByteArray(Charsets.UTF_8))
}
