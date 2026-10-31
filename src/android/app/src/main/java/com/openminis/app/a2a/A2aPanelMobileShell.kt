package com.openminis.app.a2a

import android.webkit.WebView

/**
 * Mobile wiring for the bundled a2a panel.
 *
 * [T-android-a2a-mobile] The gateway ships a complete phone layout
 * (`css/mobile.css` — 42 KB, entirely inside `@media (max-width: 767px)` —
 * plus `mobile-columns.js`, which feeds the card mode its column labels),
 * but its `index.html` never wires any of it up: no stylesheet link, no
 * script tag, and none of the three nodes the pure-CSS drawer is driven by
 * (`#nav-toggle`, `.nav-scrim`, `.mobile-menu-btn`).
 *
 * Measured on the shipped panel at 412 px: the sidebar keeps its desktop
 * 68 px rail, and the account table keeps the JS-written inline
 * `min-width: 1313px` inside a 291 px viewport — so four fifths of every row
 * sits behind a horizontal scroll. That is the whole "the web UI does not
 * fit a phone" symptom; the layout was designed, it was never connected.
 *
 * This injects the missing wiring on every document load. It is purely
 * additive and idempotent: each piece is probed before it is created, and a
 * future panel build that ships its own wiring is left untouched. Desktop
 * is untouched as well — everything `mobile.css` does is media-queried
 * above 767 px, and the injected nodes are either `display: none` or taken
 * out of the shell grid, which is the trap that stylesheet's header warns
 * about (a stray in-flow child of `.shell` steals half of the 100vh
 * surplus and leaves a white band under the sidebar).
 */
internal object A2aPanelMobileShell {

    /** Idempotence guard + stylesheet probe marker, asserted by the JVM test. */
    internal const val LINK_ID = "a2a-mobile-css"

    /**
     * Marker for the scrollbar patch, also asserted by the test.
     *
     * `tokens.css` paints a desktop scrollbar on every scroller
     * (`::-webkit-scrollbar{width:var(--sb-w)}`), which on a phone is a fat
     * gray bar lying *on top of* the content — worst on `.settings-nav`, the
     * tab strip directly under the topbar, where it eats 11 px of height and
     * reads as a broken divider. `mobile.css` never touches it (it only hides
     * horizontal overflow on the tables), so the phone inherits the desktop
     * chrome. Injected as a stylesheet instead of per-element inline styles so
     * an SPA re-render cannot drop it.
     */
    internal const val PATCH_ID = "a2a-mobile-shell-css"

    /** Flag set on `window` once the wiring ran, so a re-render is a no-op. */
    internal const val GUARD = "__a2aMobileShell"

    /** Separate flag for the document-start stylesheet pass, asserted by the JVM test. */
    internal const val START_GUARD = "__a2aMobileCssWired"

    /**
     * Confines [START_SCRIPT] to the gateway's loopback document, so vendor
     * SSO pages opened inside the panel never run it. The rule matches any
     * port, which the panel needs — the gateway port is not fixed.
     */
    internal const val START_ORIGIN_RULES = "*://127.0.0.1/*"

    /**
     * Document-start wiring: the mobile stylesheet goes into the head
     * before the parser reaches the body.
     *
     * [T-android-a2a-mobile-fouc] [SCRIPT] runs from `onPageFinished`, which
     * is after the first paint — every cold start briefly shows the desktop
     * grid (68 px sidebar + the 1313 px table) and then snaps into the card
     * layout. `mobile.css` is a render-blocking stylesheet, so a link created
     * here makes the very first frame the phone layout.
     *
     * It is registered through
     * [androidx.webkit.WebViewCompat.addDocumentStartJavaScript] and uses
     * its own guard because it must NOT reuse [GUARD]: the full [SCRIPT]
     * re-checks that flag and would skip the drawer nodes.
     */
    val START_SCRIPT: String = """
        (function () {
          if (window.$START_GUARD) return;
          window.$START_GUARD = true;
          var link = document.createElement('link');
          link.rel = 'stylesheet';
          link.href = window.location.origin + '/css/mobile.css';
          link.id = '$LINK_ID';
          (document.head || document.documentElement).appendChild(link);
          var patch = document.createElement('style');
          patch.id = '$PATCH_ID';
          patch.textContent = '@media (max-width: 767px){'
            + 'html,body,body *{scrollbar-width:none;}'
            + 'body ::-webkit-scrollbar{width:0;height:0;}}';
          (document.head || document.documentElement).appendChild(patch);
        })();
    """.trimIndent()

    /**
     * The wiring script. The only interpolations are [GUARD], [LINK_ID] and
     * [PATCH_ID], so the test can assert the rest verbatim and the
     * guard/markers cannot drift from the constants that describe them.
     *
     * Loads are resolved against the origin root rather than the document
     * URL: the panel is an SPA, and a pathname route would otherwise resolve
     * `css/mobile.css` one directory too deep.
     */
    val SCRIPT: String = """
        (function () {
          if (window.$GUARD) return;
          window.$GUARD = true;
          var d = document;
          var shell = d.querySelector('.shell');
          if (!shell) return;
          var root = window.location.origin + '/';
          if (!d.querySelector('link[href*="mobile.css"]')) {
            var link = d.createElement('link');
            link.rel = 'stylesheet';
            link.href = root + 'css/mobile.css';
            link.id = '$LINK_ID';
            d.head.appendChild(link);
          }
          if (!d.getElementById('$PATCH_ID')) {
            var patch = d.createElement('style');
            patch.id = '$PATCH_ID';
            patch.textContent = '@media (max-width: 767px){'
              + 'html,body,body *{scrollbar-width:none;}'
              + 'body ::-webkit-scrollbar{width:0;height:0;}}';
            d.head.appendChild(patch);
          }
          var sidebar = shell.querySelector('.sidebar');
          if (sidebar && !d.getElementById('nav-toggle')) {
            var toggle = d.createElement('input');
            toggle.type = 'checkbox';
            toggle.id = 'nav-toggle';
            toggle.className = 'nav-toggle-input';
            shell.insertBefore(toggle, shell.firstChild);
          }
          if (sidebar && !d.querySelector('.nav-scrim')) {
            var scrim = d.createElement('label');
            scrim.className = 'nav-scrim';
            scrim.setAttribute('for', 'nav-toggle');
            sidebar.parentNode.insertBefore(scrim, sidebar.nextSibling);
          }
          var topbar = d.querySelector('.topbar');
          if (topbar && !d.querySelector('.mobile-menu-btn')) {
            var menu = d.createElement('label');
            menu.className = 'mobile-menu-btn';
            menu.setAttribute('for', 'nav-toggle');
            menu.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" '
              + 'stroke="currentColor" stroke-width="2" stroke-linecap="round">'
              + '<path d="M4 6h16M4 12h16M4 18h16"/></svg>';
            topbar.insertBefore(menu, topbar.firstChild);
          }
          if (!d.querySelector('script[src*="mobile-columns.js"]')) {
            var columns = d.createElement('script');
            columns.src = root + 'mobile-columns.js';
            d.body.appendChild(columns);
          }
        })();
    """.trimIndent()

    /**
     * Wire the panel's shipped mobile layout into [webView]'s current
     * document. Call from `onPageFinished` — the script needs the static
     * shell (`.shell` / `.sidebar` / `.topbar`) in place, and re-running it
     * on the same document is a no-op.
     */
    fun applyTo(webView: WebView?) {
        webView ?: return
        webView.evaluateJavascript(SCRIPT, null)
    }
}
