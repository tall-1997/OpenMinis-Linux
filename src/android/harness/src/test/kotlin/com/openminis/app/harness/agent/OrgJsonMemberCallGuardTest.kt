package com.openminis.app.harness.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CRASH-2026-10-10] 真机闪退守卫。
 *
 * 设备 ART core-libart 的 org.json 没有 `JSONArray.toList()` / `JSONObject.toMap()`
 * （org.json 桌面版 2016+ 才有），而 compileSdk 35 的 android.jar 有。Kotlin
 * 成员方法优先于同文件扩展函数——一旦写出 `jsonArray.toList()`，编译器解析到
 * android.jar 的成员并生成 invokevirtual，真机直接 NoSuchMethodError 闪退；
 * JVM 测试跑在 org.json:json（testImplementation）上成员存在，永远测不出。
 *
 * 本测试解析编译产物 .class 常量池，钉死 Methodref/InterfaceMethodref 不指向
 * 这两个桌面版独有成员。以后任何人在 org.json 接收者上写出 toMap()/toList()
 * （或新增其他桌面版独有 API），这里构建期就红，不炸手机。
 */
class OrgJsonMemberCallGuardTest {

    /** 类名（内部格式）→ 被禁成员方法名。 */
    private val banned: Map<String, Set<String>> = mapOf(
        "org/json/JSONArray" to setOf("toList"),
        "org/json/JSONObject" to setOf("toMap"),
    )

    @Test
    fun `compiled bytecode never calls org-json desktop-only members`() {
        val roots = listOfNotNull(
            File("build/classes/kotlin/main"),
            File("../app/build/classes/kotlin/main").takeIf { it.isDirectory },
        )
        assertTrue("no compiled class roots found (cwd=${File(".").absolutePath})", roots.isNotEmpty())
        val offenders = roots
            .flatMap { root -> root.walkTopDown().filter { it.extension == "class" }.toList() }
            .flatMap { scan(it) }
            .sorted()
        assertTrue(
            "banned org.json member calls found (would crash on device):\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    /** 最小常量池解析：返回本文件里指向被禁成员的方法引用描述。 */
    private fun scan(f: File): List<String> {
        val b = f.readBytes()
        if (b.size < 12) return emptyList()
        fun u2(p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        val count = u2(8)
        val utf8 = HashMap<Int, String>()
        val cls = HashMap<Int, Int>()
        val nat = HashMap<Int, Pair<Int, Int>>()
        val refs = ArrayList<Pair<Int, Int>>() // classIndex → nameAndTypeIndex
        var i = 10
        var idx = 1
        while (idx < count) {
            when (val tag = b[i].toInt() and 0xFF) {
                1 -> { i++; val len = u2(i); i += 2; utf8[idx] = String(b, i, len, Charsets.UTF_8); i += len }
                7 -> { i++; cls[idx] = u2(i); i += 2 }
                9, 10, 11 -> { i++; refs.add(u2(i) to u2(i + 2)); i += 4 }
                12 -> { i++; nat[idx] = u2(i) to u2(i + 2); i += 4 }
                3, 4 -> i += 5
                5, 6 -> { i += 9; idx++ } // long/double 占双槽
                8, 16, 19, 20 -> i += 3
                15 -> i += 4
                17, 18 -> i += 5
                else -> error("unknown constant-pool tag $tag in ${f.name}")
            }
            idx++
        }
        val out = ArrayList<String>()
        for ((ci, ni) in refs) {
            val cn = utf8[cls[ci] ?: continue] ?: continue
            val names = banned[cn] ?: continue
            val (nameIdx, _) = nat[ni] ?: continue
            val name = utf8[nameIdx] ?: continue
            if (name in names) out += "${f.name}: $cn.$name"
        }
        return out
    }
}
