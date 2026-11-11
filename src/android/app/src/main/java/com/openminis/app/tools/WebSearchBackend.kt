package com.openminis.app.tools

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [GH#298] web_search 的可插拔后端扩展点。
 *
 * 历史实现把引擎选择写死在 [WebSearchTool] 的 `when (engine)` 里：内置引擎
 * 由 [WebSearchSettings.Engine] 枚举驱动，用户只能选枚举里已有的东西。这个
 * 接口把“搜索从哪来”抽出来，插件 / 未来后端不必再改工具主体：
 *
 *  - 实现本接口并通过 [WebSearchBackendRegistry.register] 注册；
 *  - 注册后的后端在 [WebSearchTool.execute] 的引擎链之前被调用，返回非空结果
 *    即视为命中（`id` 会出现在结果首行，便于排查是谁答的）；
 *  - 返回空列表 / 抛异常都只是“这个后端没结果”，会继续走既有引擎链。
 *
 * 刻意保持“先注册者先试”的简单语义：多后端聚合 / 排序属于上层策略，不在
 * 这一版里引入。默认实现见 [DuckDuckGoBackend]（无 key、走 HTML 端点）。
 * [GH#298] 它在应用启动时通过 [WebSearchBackendRegistry.ensureDefaultRegistered]
 * 真正注册进运行期注册表 —— 只定义不注册等于把扩展点做成死代码。工具在用户
 * 明确选择 Tavily / SearXNG 等其它引擎时会跳过这个默认 DDG 后端，避免内置
 * 兜底抢在用户配置之前。
 */
interface WebSearchBackend {
    /** 结果首行展示的后端标识，如 "duckduckgo" / "my-searxng"。 */
    val id: String

    /**
     * 执行一次搜索。实现方负责超时与异常收敛；[context] 可能为 null（测试 /
     * 无 Context 的调用点），需要 Context 的后端应自行降级而不是崩溃。
     */
    fun search(query: String, maxResults: Int, context: Context?): List<WebSearchTool.Result>
}

/**
 * [GH#298] 无 key 的默认后端实现：DuckDuckGo HTML / Lite。
 *
 * 复用 [WebSearchTool] 内部的抓取与解析逻辑（含反爬拦截页识别），保证插件
 * 后端与内置引擎看到的是同一份“可用结果”，而不是另写一套易碎的 HTML 正则。
 */
internal class DuckDuckGoBackend : WebSearchBackend {
    override val id: String = "duckduckgo"

    override fun search(query: String, maxResults: Int, context: Context?): List<WebSearchTool.Result> =
        WebSearchTool.searchDuckDuckGoForBackend(query, maxResults, context)
}

/**
 * [GH#298] 进程内后端注册表。线程安全（CopyOnWriteArrayList）：注册发生在
 * 设置/插件加载线程，搜索发生在 IO 线程，读多写少。
 *
 * 生命周期：进程级，不做持久化 —— 注册是“本次运行启用”的语义，持久化属于
 * 插件配置层（[WebSearchSettings]）。
 */
object WebSearchBackendRegistry {

    /** 内置默认实现；启动时由 [ensureDefaultRegistered] 放进运行期注册表。 */
    val DEFAULT: WebSearchBackend = DuckDuckGoBackend()

    private val registered = CopyOnWriteArrayList<WebSearchBackend>()

    /**
     * [GH#298] 默认后端的一次性注册标记。进程级：重复调用不会把同一个
     * backend 反复插入（否则每次启动/每次初始化都会改变顺序或产生重复）。
     */
    @Volatile
    private var defaultRegistered = false

    /**
     * [GH#298] 把 [DEFAULT] 注册进运行期注册表（幂等）。由 `MinisApp.onCreate`
     * 调用——这是“可插拔后端”扩展点的生产注册点，没有它 [DuckDuckGoBackend]
     * 永远不会被任何真实调用走到。
     *
     * @return true 表示本次调用完成了首次注册。
     */
    fun ensureDefaultRegistered(): Boolean {
        if (defaultRegistered) return false
        synchronized(this) {
            if (defaultRegistered) return false
            // 走公开的 register()，这样启动路径就是扩展点的正式生产注册点
            // （而不是绕过 registry 直接改内部列表）。启动时表为空，因此默认
            // 后端自然排在最前；用户显式选择其它引擎时工具层会跳过它。
            register(DEFAULT)
            defaultRegistered = true
            return true
        }
    }

    /** 注册一个后端；同 id 已存在时替换，返回是否发生了替换。 */
    fun register(backend: WebSearchBackend): Boolean {
        if (backend.id.isBlank()) return false
        val replaced = registered.removeAll { it.id == backend.id }
        registered.add(backend)
        return replaced
    }

    fun unregister(id: String): Boolean = registered.removeAll { it.id == id }

    /** 当前生效的自定义后端，按注册顺序。 */
    fun backends(): List<WebSearchBackend> = registered.toList()

    /** 测试清理入口。 */
    internal fun clearForTest() {
        registered.clear()
        defaultRegistered = false
    }
}
