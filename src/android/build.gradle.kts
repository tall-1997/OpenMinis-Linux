import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.test") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

// ==================== 架构治理（policy 即代码） ====================
// 规则唯一事实源：architecture-policy.json
//   - 模块依赖白名单 requires（补齐反向依赖检查：core/harness/provider 依赖 app 即失败）
//   - 依赖图无环（forbidCycles）
//   - import 黑名单 importBans（JVM 层纯 Kotlin，禁 android/androidx）
//   - 文件尺寸棘轮 global.maxFileLines + .architecture-baseline.json（存量超限文件只许缩减不许上涨）
// 基线维护：运行 architectureBaselineSync（收缩下调 / 拒绝上涨 / 清理失效条目）。
//
// 配置缓存约束（gradle.properties: org.gradle.configuration-cache=true）：
// 任务动作只能捕获 File / String / List 这类可序列化的值，不能引用脚本级 val 或
// 脚本函数 —— 那会捕获 Build_gradle 实例，配置缓存无法反序列化（action 直接报
// "this.this$0 is null"）。因此路径与 policy 数据一律在配置 lambda 内以局部 val
// 求值，动作内的辅助函数就地定义、只接收参数。
fun architectureReadPolicy(file: java.io.File): Map<*, *> =
    JsonSlurper().parseText(file.readText().trim().removePrefix("\uFEFF")) as Map<*, *>

// 门禁挂载：所有子模块的构建入口先过架构检查。
// - preBuild：Android 模块的 assemble/compile 路径（CI 的 :app:assembleRelease 由此进入）
// - classes：JVM 模块的编译路径（:harness:test 经由 testClasses -> classes 也被覆盖）
// - check：显式跑检查的路径（lint/test 汇总入口）
// - compile*Kotlin：直连编译任务（CI 的 :app:compileReleaseKotlin 走这条，不经过
//   preBuild/classes），变体名如 compileDebugKotlin / compileReleaseKotlin。
// 本任务只读 src/main 与 policy/baseline，不依赖任何编译产物，因此不成环。
// matching 对不存在的任务名自动跳过，故可安全用于异构模块集合。
subprojects {
    tasks.matching {
        it.name == "preBuild" || it.name == "classes" || it.name == "check" ||
            (it.name.startsWith("compile") && it.name.endsWith("Kotlin"))
    }.configureEach { dependsOn(":architectureCheck") }
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Policy-driven checks: dependency whitelist, cycles, import bans, size ratchet."

    val rootDirFile = rootProject.layout.projectDirectory.asFile
    val policyFile = rootDirFile.resolve("architecture-policy.json")
    val policySnapshot = architectureReadPolicy(policyFile)
    val globalSnapshot = policySnapshot["global"] as Map<*, *>
    val baselineFile = rootDirFile.resolve(globalSnapshot["baselineFile"] as String)
    @Suppress("UNCHECKED_CAST")
    val moduleDirs = (policySnapshot["modules"] as List<Map<*, *>>)
        .map { rootDirFile.resolve(it["path"] as String) }

    inputs.files(policyFile)
    if (baselineFile.isFile) inputs.files(baselineFile)
    moduleDirs.forEach { moduleDir ->
        inputs.files(moduleDir.resolve("build.gradle.kts"))
        inputs.files(fileTree(moduleDir.resolve("src/main")) { include("**/*.kt") })
    }

    doLast {
        fun readPolicy(file: java.io.File): Map<*, *> =
            JsonSlurper().parseText(file.readText().trim().removePrefix("\uFEFF")) as Map<*, *>

        fun moduleSourceRoot(module: Map<*, *>): java.io.File =
            rootDirFile.resolve(module["path"] as String).resolve("src/main")

        fun moduleGradlePath(module: Map<*, *>): String =
            ":${(module["path"] as String).replace('/', ':')}"

        val policy = readPolicy(policyFile)
        val global = policy["global"] as Map<*, *>
        val maxFileLines = (global["maxFileLines"] as Number).toInt()
        val effectiveBaselineFile = rootDirFile.resolve(global["baselineFile"] as String)

        @Suppress("UNCHECKED_CAST")
        val modules = policy["modules"] as List<Map<*, *>>
        val idByGradlePath = modules.associate { moduleGradlePath(it) to it["id"] as String }
        val moduleById = modules.associateBy { it["id"] as String }

        val violations = mutableListOf<String>()
        // 捕获至引号前：模块名可含连字符
        val projectDepRegex = Regex("""project\(":([^"\s]+)""")

        // ---- 规则 1：模块依赖白名单 + 未登记依赖 ----
        val declaredDeps = mutableMapOf<String, List<String>>()
        modules.forEach { module ->
            val id = module["id"] as String
            val buildFile = rootDirFile.resolve(module["path"] as String).resolve("build.gradle.kts")
            if (!buildFile.isFile) {
                violations += "policy 模块 $id 的构建脚本不存在: ${buildFile.relativeTo(rootDirFile).invariantSeparatorsPath}"
                return@forEach
            }
            // 剥离 // 注释行，避免注释中的示例引用被当作依赖
            val buildText = buildFile.readLines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            val allowed = (module["requires"] as List<*>).map { it as String }.toSet()
            val deps = mutableListOf<String>()
            projectDepRegex.findAll(buildText).forEach { match ->
                val depId = idByGradlePath[":${match.groupValues[1]}"]
                when {
                    depId == null ->
                        violations += "模块 $id 依赖了未登记的模块 :${match.groupValues[1]}（请同步 architecture-policy.json）"
                    depId !in allowed ->
                        violations += "模块 $id 依赖 $depId，不在其 requires 白名单中"
                    else -> deps += depId
                }
            }
            declaredDeps[id] = deps
            // 构建脚本字符串黑名单（如模型层禁平台插件）
            (module["forbidBuildScript"] as? List<*>)?.forEach { forbidden ->
                if (buildText.contains(forbidden as String)) {
                    violations += "模块 $id 构建脚本出现被禁止的声明: $forbidden"
                }
            }
        }

        // ---- 规则 2：依赖图无环（DFS 三色标记）----
        if (global["forbidCycles"] == true) {
            val state = mutableMapOf<String, Int>() // 1=在栈中 2=已完成
            fun dfs(node: String, stack: List<String>) {
                when (state[node]) {
                    1 -> {
                        val start = stack.indexOf(node).coerceAtLeast(0)
                        violations += "模块依赖环: ${(stack.drop(start) + node).joinToString(" -> ")}"
                        return
                    }
                    2 -> return
                }
                state[node] = 1
                declaredDeps[node].orEmpty().forEach { dfs(it, stack + node) }
                state[node] = 2
            }
            declaredDeps.keys.forEach { if (state[it] == null) dfs(it, emptyList()) }
        }

        // ---- 规则 3：import 黑名单（逐行匹配 src/main 下 .kt）----
        val groupMembers = (policy["groups"] as? Map<*, *>)
            ?.mapValues { (_, v) -> (v as List<*>).map { it as String }.toSet() }
            ?: emptyMap()
        (policy["importBans"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { ban ->
            val banId = ban["id"] as String
            val pattern = Regex(ban["pattern"] as String)
            val message = ban["message"] as? String ?: "命中 import 黑名单 $banId"
            val targets = mutableSetOf<String>()
            (ban["modules"] as? List<*>)?.forEach { targets += it as String }
            (ban["groups"] as? List<*>)?.forEach { g -> targets += groupMembers[g as String].orEmpty() }
            targets.forEach { targetId ->
                val module = moduleById[targetId]
                if (module == null) {
                    violations += "importBans[$banId] 引用了未定义模块 $targetId"
                    return@forEach
                }
                val srcRoot = moduleSourceRoot(module)
                if (!srcRoot.isDirectory) return@forEach
                srcRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { source ->
                        source.useLines { lines ->
                            lines.forEachIndexed { index, line ->
                                if (pattern.containsMatchIn(line)) {
                                    violations += "${source.relativeTo(rootDirFile).invariantSeparatorsPath}:${index + 1}: $message"
                                }
                            }
                        }
                    }
            }
        }

        // ---- 规则 4：文件尺寸棘轮（基线豁免存量，只许缩减）----
        val baselineFiles: Map<*, *> = if (effectiveBaselineFile.isFile) {
            (readPolicy(effectiveBaselineFile)["files"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        } else {
            emptyMap<Any?, Any?>()
        }
        modules.forEach { module ->
            val srcRoot = moduleSourceRoot(module)
            if (!srcRoot.isDirectory) return@forEach
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    val lineCount = source.useLines { it.count() }
                    if (lineCount > maxFileLines) {
                        val rel = source.relativeTo(rootDirFile).invariantSeparatorsPath
                        val baselineValue = (baselineFiles[rel] as? Number)?.toInt()
                        when {
                            baselineValue == null ->
                                violations += "$rel 共 $lineCount 行，超过 maxFileLines=$maxFileLines（新文件必须合规；确属存量请运行 architectureBaselineSync 登记）"
                            lineCount > baselineValue ->
                                violations += "$rel 增长到 $lineCount 行，超过棘轮基线 $baselineValue（基线只许下调，请先缩减该文件）"
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                violations.joinToString(prefix = "\nArchitecture violations (${violations.size}):\n- ", separator = "\n- ")
            )
        }
        println("architectureCheck: ${modules.size} 个模块 · 依赖白名单/无环/import 黑名单/尺寸棘轮 全部通过")
    }
}

tasks.register("architectureBaselineSync") {
    group = "verification"
    description = "同步文件尺寸棘轮基线：收缩下调 / 拒绝上涨 / 清理失效条目。"

    // 与 architectureCheck 共享 .architecture-baseline.json（check 读、sync 写），
    // 同次调用时 Gradle 需要显式顺序：check 先跑，否则 sync 会先把新增超限文件
    // 登记进基线、让 check 恒过，棘轮门禁被绕过。
    mustRunAfter("architectureCheck")

    val rootDirFile = rootProject.layout.projectDirectory.asFile
    val policyFile = rootDirFile.resolve("architecture-policy.json")
    val policySnapshot = architectureReadPolicy(policyFile)
    val globalSnapshot = policySnapshot["global"] as Map<*, *>
    val baselineFile = rootDirFile.resolve(globalSnapshot["baselineFile"] as String)
    @Suppress("UNCHECKED_CAST")
    val moduleDirs = (policySnapshot["modules"] as List<Map<*, *>>)
        .map { rootDirFile.resolve(it["path"] as String) }

    inputs.files(policyFile)
    if (baselineFile.isFile) inputs.files(baselineFile)
    moduleDirs.forEach { moduleDir ->
        inputs.files(fileTree(moduleDir.resolve("src/main")) { include("**/*.kt") })
    }
    outputs.file(baselineFile)

    doLast {
        fun readPolicy(file: java.io.File): Map<*, *> =
            JsonSlurper().parseText(file.readText().trim().removePrefix("\uFEFF")) as Map<*, *>

        val policy = readPolicy(policyFile)
        val global = policy["global"] as Map<*, *>
        val maxFileLines = (global["maxFileLines"] as Number).toInt()
        val effectiveBaselineFile = rootDirFile.resolve(global["baselineFile"] as String)

        val baseline: MutableMap<Any?, Any?> = if (effectiveBaselineFile.isFile) {
            @Suppress("UNCHECKED_CAST")
            (readPolicy(effectiveBaselineFile)["files"] as? Map<Any?, Any?>)?.toMutableMap() ?: mutableMapOf()
        } else {
            mutableMapOf()
        }

        val oversized = mutableMapOf<String, Int>()
        @Suppress("UNCHECKED_CAST")
        val policyModules = policy["modules"] as List<Map<*, *>>
        policyModules.forEach { module ->
            val srcRoot = rootDirFile.resolve(module["path"] as String).resolve("src/main")
            if (!srcRoot.isDirectory) return@forEach
            srcRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { source ->
                    val lineCount = source.useLines { it.count() }
                    if (lineCount > maxFileLines) {
                        oversized[source.relativeTo(rootDirFile).invariantSeparatorsPath] = lineCount
                    }
                }
        }

        var added = 0
        var lowered = 0
        var refused = 0
        var cleaned = 0
        oversized.forEach { (rel, lines) ->
            val old = (baseline[rel] as? Number)?.toInt()
            when {
                old == null -> { baseline[rel] = lines; added++ }
                lines < old -> { baseline[rel] = lines; lowered++ }
                lines > old -> refused++
            }
        }
        baseline.keys.toList().forEach { key ->
            val rel = key as String
            val file = rootDirFile.resolve(rel)
            if (!file.isFile || rel !in oversized) {
                baseline.remove(key)
                cleaned++
            }
        }

        val doc = linkedMapOf<String, Any?>(
            "_doc" to listOf(
                "架构棘轮基线：收录存量超过 maxFileLines 的源文件（行数快照）。",
                "规则：条目行数只能下调（代码缩减后应下调或删除条目）；文件行数超过基线值 = 架构违规。",
                "不在基线中的新文件必须 <= architecture-policy.json 的 global.maxFileLines。",
                "维护：运行 ./gradlew architectureBaselineSync 自动同步。"
            ),
            "generatedAt" to java.time.LocalDate.now().toString(),
            "maxFileLines" to maxFileLines,
            "fileCount" to baseline.size,
            "files" to baseline.toSortedMap(compareBy { it.toString() })
        )
        effectiveBaselineFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(doc)))
        println("architectureBaselineSync: 新增 $added · 下调 $lowered · 拒绝上涨 $refused · 清理 $cleaned · 基线条目 ${baseline.size}")
        if (refused > 0) {
            throw GradleException("有 $refused 个基线文件行数上涨（棘轮拒绝生效）。请先缩减这些文件，再重新运行 sync。")
        }
    }
}
