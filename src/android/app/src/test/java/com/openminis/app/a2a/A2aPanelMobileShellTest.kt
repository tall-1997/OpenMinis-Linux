package com.openminis.app.a2a

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-a2a-mobile] The panel ships `css/mobile.css` + `mobile-columns.js`
 * but its `index.html` never references either, so the phone renders the
 * desktop grid (1313px table inside a 291px viewport). The injected wiring has
 * two properties that break silently and look identical in a screenshot, so
 * they are pinned here instead of trusted:
 *
 *  - **Position.** `mobile.css` opens the drawer with
 *    `#nav-toggle:checked ~ .sidebar`, a *following-sibling* selector. A
 *    checkbox appended anywhere after the sidebar satisfies the HTML and does
 *    nothing on a phone.
 *  - **Idempotence.** `onPageFinished` fires per document load, and a future
 *    panel build may wire itself. Every piece must be probed first, or a
 *    second run stacks duplicate checkboxes and scrims on the shell grid.
 */
class A2aPanelMobileShellTest {

    private val script = A2aPanelMobileShell.SCRIPT

    @Test
    fun `the wiring runs once per document`() {
        assertTrue(script.contains("window.${A2aPanelMobileShell.GUARD}"))
        assertTrue(script.trimStart().startsWith("(function () {"))
    }

    @Test
    fun `the stylesheet probe marker is the documented id`() {
        assertTrue(script.contains("link.id = '${A2aPanelMobileShell.LINK_ID}'"))
        assertTrue(script.contains("link[href*=\"mobile.css\"]"))
    }

    @Test
    fun `the desktop scrollbar is suppressed on phones`() {
        // `tokens.css` gives every scroller a 10px classic scrollbar; the
        // panel's own mobile.css never overrides it, so the settings tab
        // strip renders a gray bar on top of its own content. Pinned as a
        // stylesheet (not inline styles) so an SPA re-render cannot drop it.
        assertTrue(script.contains("patch.id = '${A2aPanelMobileShell.PATCH_ID}'"))
        assertTrue(script.contains("d.getElementById('${A2aPanelMobileShell.PATCH_ID}')"))
        assertTrue(script.contains("scrollbar-width:none"))
        assertTrue(script.contains("::-webkit-scrollbar{width:0;height:0;}"))
        // Width-zero scrollbars only make sense inside the phone breakpoint.
        assertTrue(script.contains("@media (max-width: 767px){"))
    }

    @Test
    fun `stylesheet and column labels resolve from the site root`() {
        // The panel is an SPA: a pathname route turns a relative href into
        // /accounts/css/mobile.css, which the gateway does not serve.
        assertTrue(script.contains("window.location.origin + '/'"))
        assertTrue(script.contains("root + 'css/mobile.css'"))
        assertTrue(script.contains("root + 'mobile-columns.js'"))
    }

    @Test
    fun `drawer checkbox is inserted before the sidebar`() {
        // `#nav-toggle:checked ~ .sidebar` only matches a PRECEDING sibling.
        assertTrue(script.contains("shell.insertBefore(toggle, shell.firstChild)"))
    }

    @Test
    fun `scrim follows the sidebar and the menu button leads the topbar`() {
        assertTrue(script.contains("sidebar.parentNode.insertBefore(scrim, sidebar.nextSibling)"))
        assertTrue(script.contains("topbar.insertBefore(menu, topbar.firstChild)"))
    }

    @Test
    fun `every injected node is probed before it is created`() {
        // One guard per piece — a half-wired upstream build keeps its own
        // nodes and only gets the missing ones.
        assertTrue(script.contains("if (sidebar && !d.getElementById('nav-toggle'))"))
        assertTrue(script.contains("if (sidebar && !d.querySelector('.nav-scrim'))"))
        assertTrue(script.contains("if (topbar && !d.querySelector('.mobile-menu-btn'))"))
        assertTrue(script.contains("if (!d.querySelector('script[src*=\"mobile-columns.js\"]'))"))
    }

    @Test
    fun `the shell is required but a missing topbar is tolerated`() {
        // `.shell` is the static page skeleton (every render has it); a
        // missing topbar would mean the document changed shape and the rest
        // of the wiring must not throw on the way out.
        assertTrue(script.contains("var shell = d.querySelector('.shell');"))
        assertTrue(script.contains("if (!shell) return;"))
        assertTrue(script.contains("if (topbar && "))
    }
}
