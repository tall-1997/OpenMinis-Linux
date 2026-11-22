import com.openminis.app.ui.tokens.MinisColors
import com.openminis.app.ui.tokens.MinisDimens
import com.openminis.app.ui.tokens.MinisType

/**
 * 运行时断言：在真 JVM 上实例化 MinisColors.Light / Dark，验证语义值。
 *
 * check_token_semantics.py 是静态文本分析，能抓结构错误（漏填、方向反了）；
 * 这个程序是运行期验证，能抓「解析对了但值算错了」—— 两者互补。
 *
 * 编译：kotlinc MinisTokens.kt TokensProbe.kt -include-runtime -d probe.jar
 * 运行：java -jar probe.jar
 */
private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
    } else {
        fail++
        println("  ✗ $name${if (detail.isNotEmpty()) " — $detail" else ""}")
    }
}

private fun lum(argb: Long): Double {
    fun ch(c: Long): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
}

private fun contrast(a: Long, b: Long): Double {
    val la = lum(a)
    val lb = lum(b)
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

private fun hex(v: Long) = "#%08X".format(v)

fun main() {
    val L = MinisColors.Light
    val D = MinisColors.Dark

    println("=== MinisColors 运行期断言 ===")

    // 1. 深色背景必须真的比浅色暗
    check("Dark.background 比 Light 暗",
        lum(D.background) < lum(L.background),
        "Light=${hex(L.background)} Dark=${hex(D.background)}")
    check("Dark.surface 比 Light 暗",
        lum(D.surface) < lum(L.surface))
    check("Dark.cardBg 比 Light 暗",
        lum(D.cardBg) < lum(L.cardBg))
    check("Dark.editorCardBg 比 Light 暗",
        lum(D.editorCardBg) < lum(L.editorCardBg))
    check("Dark.editorCardHeaderBg 比 Light 暗",
        lum(D.editorCardHeaderBg) < lum(L.editorCardHeaderBg))

    // 2. 深色文字必须真的比浅色亮
    check("Dark.textPrimary 比 Light 亮",
        lum(D.textPrimary) > lum(L.textPrimary))
    check("Dark.textSecondary 比 Light 亮",
        lum(D.textSecondary) > lum(L.textSecondary))
    check("Dark.onBackground 比 Light 亮",
        lum(D.onBackground) > lum(L.onBackground))

    // 3. 编辑器卡片三段灰阶的层次关系（iOS 观感契约）
    check("Light 编辑器 header 比 body 暗一档（iOS white:0.92 vs 0.94）",
        lum(L.editorCardHeaderBg) < lum(L.editorCardBg),
        "header=${hex(L.editorCardHeaderBg)} body=${hex(L.editorCardBg)}")
    check("Dark 编辑器 header 比 body 亮一档（iOS white:0.13 vs 0.10）",
        lum(D.editorCardHeaderBg) > lum(D.editorCardBg),
        "header=${hex(D.editorCardHeaderBg)} body=${hex(D.editorCardBg)}")

    // 4. diff 配色方向：删除偏红、新增偏绿，两套主题都成立
    for ((name, c) in listOf("Light" to L, "Dark" to D)) {
        val addR = (c.diffAddedBg shr 16) and 0xFF
        val addG = (c.diffAddedBg shr 8) and 0xFF
        val remR = (c.diffRemovedBg shr 16) and 0xFF
        val remG = (c.diffRemovedBg shr 8) and 0xFF
        check("$name diffAddedBg 偏绿 (G>R)", addG > addR,
            "G=$addG R=$addR")
        check("$name diffRemovedBg 偏红 (R>G)", remR > remG,
            "R=$remR G=$remG")
        check("$name diffAddedText 在 diffAddedBg 上可读",
            contrast(c.diffAddedText, c.diffAddedBg) >= 4.5,
            "%.2f:1".format(contrast(c.diffAddedText, c.diffAddedBg)))
        check("$name diffRemovedText 在 diffRemovedBg 上可读",
            contrast(c.diffRemovedText, c.diffRemovedBg) >= 4.5,
            "%.2f:1".format(contrast(c.diffRemovedText, c.diffRemovedBg)))
    }

    // 5. 正文对比度 AA
    for ((name, c) in listOf("Light" to L, "Dark" to D)) {
        check("$name textPrimary 对比度 >= 4.5",
            contrast(c.textPrimary, c.background) >= 4.5,
            "%.2f:1".format(contrast(c.textPrimary, c.background)))
        check("$name textSecondary 对比度 >= 4.5",
            contrast(c.textSecondary, c.background) >= 4.5,
            "%.2f:1".format(contrast(c.textSecondary, c.background)))
        check("$name textPrimary 在 cardBg 上可读",
            contrast(c.textPrimary, c.cardBg) >= 4.5,
            "%.2f:1".format(contrast(c.textPrimary, c.cardBg)))
        check("$name textPrimary 在 editorCardBg 上可读",
            contrast(c.textPrimary, c.editorCardBg) >= 4.5,
            "%.2f:1".format(contrast(c.textPrimary, c.editorCardBg)))
    }

    // 6. 终端槽位两套主题同值（终端永远深底）
    check("terminalBg 两套同值", L.terminalBg == D.terminalBg)
    check("terminalBorder 两套同值", L.terminalBorder == D.terminalBorder)
    check("terminalLink 两套同值", L.terminalLink == D.terminalLink)
    check("terminalLink 在 terminalBg 上可读",
        contrast(L.terminalLink, L.terminalBg) >= 3.0,
        "%.2f:1".format(contrast(L.terminalLink, L.terminalBg)))

    // 7. URL 胶囊：iOS 固定白灰，两套同值
    check("urlCapsuleBg 两套同值", L.urlCapsuleBg == D.urlCapsuleBg)
    check("urlCapsuleText 在 urlCapsuleBg 上可读",
        contrast(L.urlCapsuleText, L.urlCapsuleBg) >= 4.5,
        "%.2f:1".format(contrast(L.urlCapsuleText, L.urlCapsuleBg)))

    // 8. accent 两套同值（设计意图：accent 不随主题翻转）
    check("link 两套同值", L.link == D.link)
    check("toolSuccess 两套同值", L.toolSuccess == D.toolSuccess)
    check("sendEnabled 两套同值", L.sendEnabled == D.sendEnabled)

    // 9. MinisDimens.Compact 只缩间距、不动触摸目标
    val compact = MinisDimens.Compact
    val base = MinisDimens()
    check("Compact.spaceMd < 默认", compact.spaceMd < base.spaceMd,
        "compact=${compact.spaceMd} base=${base.spaceMd}")
    check("Compact 不改 touchTargetMin（无障碍硬要求）",
        compact.touchTargetMin == base.touchTargetMin,
        "compact=${compact.touchTargetMin} base=${base.touchTargetMin}")
    check("touchTargetMin >= 48dp", base.touchTargetMin >= 48f,
        "%.1f".format(base.touchTargetMin))

    // 10. 间距阶梯在运行期仍然单调 —— 静态检查按声明序推导，这里核对真实取值。
    // 旧版 Compact 只覆盖 5 个槽，于是 spaceMd2(10) > spaceLg(8) 倒挂：排版会
    // 忽紧忽松，但编译通过、渲染也不报错，肉眼极难归因到令牌。
    fun ladderOf(d: MinisDimens) = listOf(
        "spaceXxs" to d.spaceXxs, "spaceXs" to d.spaceXs,
        "spaceSm" to d.spaceSm, "spaceMd" to d.spaceMd,
        "spaceMd2" to d.spaceMd2, "spaceLg" to d.spaceLg,
        "spaceLg2" to d.spaceLg2, "spaceXl" to d.spaceXl,
        "spaceXl2" to d.spaceXl2, "spaceXxl" to d.spaceXxl,
        "spaceXxl2" to d.spaceXxl2, "spaceXxl3" to d.spaceXxl3,
        "spaceXxl4" to d.spaceXxl4, "spaceHuge" to d.spaceHuge,
        "spaceHuge2" to d.spaceHuge2, "spaceGiant" to d.spaceGiant,
        "spaceGiant2" to d.spaceGiant2,
    )
    val baseMap = ladderOf(base).toMap()
    for ((seq, name) in listOf(ladderOf(base) to "默认", ladderOf(compact) to "Compact")) {
        for (i in 0 until seq.size - 1) {
            val (an, av) = seq[i]
            val (bn, bv) = seq[i + 1]
            check("$name 阶梯 $an < $bn", av < bv, "$an=%.1f $bn=%.1f".format(av, bv))
        }
    }
    // Compact 只能缩、不能涨，否则「紧凑」名不副实
    for ((n, v) in ladderOf(compact)) {
        check("Compact.$n <= 默认", v <= baseMap.getValue(n),
            "compact=%.1f base=%.1f".format(v, baseMap.getValue(n)))
    }

    // 11. 圆角阶梯同样必须递增 —— 补档时容易插错位置。原版只有 Sm/Md/Lg 三档，
    // 全仓 211 处 RoundedCornerShape 里 55% 落在三档之外（10dp 用了 44 次却没有
    // 令牌），令牌若不能表达真实设计就只是装饰。
    fun radiusOf(d: MinisDimens) = listOf(
        "radiusXs" to d.radiusXs, "radiusXs2" to d.radiusXs2,
        "radiusSm3" to d.radiusSm3, "radiusSm" to d.radiusSm,
        "radiusMd3" to d.radiusMd3, "radiusMd" to d.radiusMd,
        "radiusLg3" to d.radiusLg3, "radiusLg" to d.radiusLg,
        "radiusXl" to d.radiusXl, "radiusXl2" to d.radiusXl2,
        "radiusXxl" to d.radiusXxl, "radiusHuge" to d.radiusHuge,
        "radiusHuge2" to d.radiusHuge2,
    )
    for ((seq, name) in listOf(radiusOf(base) to "默认", radiusOf(compact) to "Compact")) {
        for (i in 0 until seq.size - 1) {
            val (an, av) = seq[i]
            val (bn, bv) = seq[i + 1]
            check("$name 圆角 $an < $bn", av < bv, "$an=%.1f $bn=%.1f".format(av, bv))
        }
    }
    check("radiusPill 远大于 radiusHuge2", base.radiusPill > 10 * base.radiusHuge2,
        "pill=%.0f huge2=%.0f".format(base.radiusPill, base.radiusHuge2))
    // 旧三档的值不能被改名顺手改掉
    check("radiusSm 仍是 8dp（存量语义）", base.radiusSm == 8f, "%.1f".format(base.radiusSm))
    check("radiusMd 仍是 12dp（存量语义）", base.radiusMd == 12f, "%.1f".format(base.radiusMd))
    check("radiusLg 仍是 16dp（存量语义）", base.radiusLg == 16f, "%.1f".format(base.radiusLg))

    // 12. 行高曲线：小字紧、正文松、显示字号回收到紧。
    // 旧版 lineHeight(size) = size * 1.35f 是一条平直线，实测数据是倒 U 形。
    val ty = MinisType()
    fun lh(sz: Float): Float = ty.lineHeight(sz)
    check("9sp 行高应偏紧 (约 11)", lh(9f) in 10.5f..11.5f, "%.2f".format(lh(9f)))
    check("12sp 行高应放宽 (约 17)", lh(12f) in 16.5f..17.5f, "%.2f".format(lh(12f)))
    check("16sp 行高应最松 (约 24)", lh(16f) in 23.5f..24.5f, "%.2f".format(lh(16f)))
    check("57sp 行高应收紧 (约 64)", lh(57f) in 63f..65f, "%.2f".format(lh(57f)))
    check("行高永远大于字号", (listOf(9f, 11f, 13f, 16f, 24f, 57f)).all { lh(it) > it })
    // 倒 U 形的关键性质：正文比小字松、正文比大字松
    check("正文 16sp 比小字 9sp 松", lh(16f) / 16f > lh(9f) / 9f,
        "16=%.3f 9=%.3f".format(lh(16f) / 16f, lh(9f) / 9f))
    check("正文 16sp 比大字 57sp 松", lh(16f) / 16f > lh(57f) / 57f,
        "16=%.3f 57=%.3f".format(lh(16f) / 16f, lh(57f) / 57f))
    check("未知字号有兜底（不为 0）", lh(200f) > 0f, "%.1f".format(lh(200f)))

    // 13. 锚点插值：中间字号必须落在相邻实测值之间。
    // 旧版 when 阶梯在 16 之后直接跳到 1.27，16.5/17/18/19/20 全被压平 ——
    // 16.5sp 输入框（实测 24）被算成 20.9，比 16sp 正文的 24 还紧。
    check("16.5sp 输入框行高应为 24（与正文同高，不能更紧）",
        lh(16.5f) in 23.5f..24.5f, "%.2f".format(lh(16.5f)))
    check("17sp 行高应介于 16 与 22 之间", lh(17f) > lh(16f) && lh(17f) < lh(22f),
        "17=%.2f 16=%.2f 22=%.2f".format(lh(17f), lh(16f), lh(22f)))
    check("18sp 行高应低于 22sp", lh(18f) < lh(22f),
        "18=%.2f 22=%.2f".format(lh(18f), lh(22f)))
    var monotone = true
    var prevH = 0f
    var sz = 8f
    while (sz <= 72f) {
        val h = lh(sz)
        if (h < prevH) monotone = false
        if (h <= sz) monotone = false
        prevH = h
        sz += 0.5f
    }
    check("行高随字号半档步进单调且恒大于字号", monotone)
    // 锚点处必须精确命中实测值，不能靠插值近似
    check("锚点 11sp = 14.5", lh(11f) in 14.4f..14.6f, "%.2f".format(lh(11f)))
    check("锚点 14sp = 20", lh(14f) in 19.9f..20.1f, "%.2f".format(lh(14f)))

    println()
    println("通过 $pass 项，失败 $fail 项")
    if (fail > 0) {
        System.exit(1)
    }
    println("PASS: 语义令牌在真实 JVM 上取值正确")
}
