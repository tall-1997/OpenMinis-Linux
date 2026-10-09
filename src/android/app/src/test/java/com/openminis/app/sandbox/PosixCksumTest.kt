package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-p1-3-shell-auth-frames] 宿主侧 POSIX cksum 与 guest 侧 `cksum` 二进制必须
 * 逐字节一致——帧认证标签两边各算一次，不等就永远配不上帧。向量实测于
 * GNU coreutils（Git Bash，bash 内建 `printf '%s' <s> | cksum`，零 CRLF 污染：
 * Windows 的 printf.exe 会追加 \r\n，长度列 +2 即暴露），改算法先改这里。
 */
class PosixCksumTest {
    @Test
    fun `matches gnu cksum vectors`() {
        // "a1b2c3d4SECRET9999" -> 3597636542 18
        assertEquals(3597636542L, PosixCksum.cksum("a1b2c3d4SECRET9999"))
        // "deadbeefFEED42" -> 1853625694 14
        assertEquals(1853625694L, PosixCksum.cksum("deadbeefFEED42"))
        // "123456789" -> 930766865 9
        assertEquals(930766865L, PosixCksum.cksum("123456789"))
    }

    @Test
    fun `empty input matches gnu cksum`() {
        // `printf '%s' '' | cksum` -> 4294967295 0
        assertEquals(4294967295L, PosixCksum.cksum(""))
    }

    @Test
    fun `length suffix participates for multi byte lengths`() {
        // 单字节与同值多字节的 CRC 不同（长度后缀生效的直接证据）
        val one = PosixCksum.cksum("a")
        val two = PosixCksum.cksum("aa")
        org.junit.Assert.assertNotEquals(one, two)
    }
}
