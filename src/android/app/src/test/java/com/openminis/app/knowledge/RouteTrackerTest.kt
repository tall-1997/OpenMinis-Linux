package com.openminis.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RouteTracker: 6-step window, consecutive-duplicate collapse, package-switch reset. */
class RouteTrackerTest {

    @Test
    fun recordsStepsInOrder() {
        val t = RouteTracker()
        t.record("s", "com.a", "我的")
        t.record("s", "com.a", "设置")
        t.record("s", "com.a", "通用")
        assertEquals(listOf("我的", "设置", "通用"), t.route("s", "com.a"))
    }

    @Test
    fun windowKeepsAtMostSixSteps() {
        val t = RouteTracker()
        val labels = listOf("一", "二", "三", "四", "五", "六", "七", "八")
        labels.forEach { t.record("s", "com.a", it) }
        val route = t.route("s", "com.a")
        assertEquals(RouteTracker.MAX_STEPS, route.size)
        assertEquals(labels.takeLast(RouteTracker.MAX_STEPS), route)
    }

    @Test
    fun consecutiveDuplicatesCollapse() {
        val t = RouteTracker()
        t.record("s", "com.a", "开关")
        t.record("s", "com.a", "开关")
        t.record("s", "com.a", " 开关 ") // whitespace-normalized, still the same label
        t.record("s", "com.a", "返回")
        t.record("s", "com.a", "开关") // after an intermediate step it records again
        assertEquals(listOf("开关", "返回", "开关"), t.route("s", "com.a"))
    }

    @Test
    fun packageSwitchResetsRoute() {
        val t = RouteTracker()
        t.record("s", "com.a", "首页")
        t.record("s", "com.a", "我的")
        t.record("s", "com.b", "首页")
        assertEquals(listOf("首页"), t.route("s", "com.b"))
        assertEquals("old package route is gone", emptyList<String>(), t.route("s", "com.a"))
    }

    @Test
    fun routeForOtherPackageOrUnknownSessionIsEmpty() {
        val t = RouteTracker()
        t.record("s", "com.a", "首页")
        assertTrue(t.route("s", "com.b").isEmpty())
        assertTrue(t.route("other", "com.a").isEmpty())
    }

    @Test
    fun blankLabelsIgnored() {
        val t = RouteTracker()
        t.record("s", "com.a", "   ")
        t.record("s", "com.a", "")
        assertTrue(t.route("s", "com.a").isEmpty())
    }

    @Test
    fun clearDropsRouteButKeepsPackage() {
        val t = RouteTracker()
        t.record("s", "com.a", "首页")
        t.clear("s")
        assertTrue(t.route("s", "com.a").isEmpty())
        t.record("s", "com.a", "设置")
        assertEquals(listOf("设置"), t.route("s", "com.a"))
    }

    @Test
    fun sessionPrefixSharesOneBucket() {
        val t = RouteTracker()
        t.record("parent", "com.a", "首页")
        t.record("parent/child", "com.a", "我的")
        assertEquals(listOf("首页", "我的"), t.route("parent", "com.a"))
        assertEquals(listOf("首页", "我的"), t.route("parent/child", "com.a"))
    }
}
