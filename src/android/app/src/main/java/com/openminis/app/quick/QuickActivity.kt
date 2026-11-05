package com.openminis.app.quick

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.settings.KEY_DYNAMIC_COLOR
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.MinisTheme

/**
 * [E-batch-quick-invocation] The summon-anywhere half-screen panel.
 *
 * Entries: floating ball (tap / double tap), quick-settings tile and the app
 * itself. The system share sheet is NOT an entry — the manifest declares no
 * ACTION_SEND filter here (ShareReceiverActivity is the single share target,
 * T157), and the formerly present (but unreachable) ACTION_SEND handling was
 * removed [Wave3 #9]. It is
 * a translucent activity showing a bottom card — it never pulls the full
 * ChatViewModel; sending runs the prompt unattended through
 * [QuickPromptDispatcher] on the app's newest session and reports completion
 * via a system notification.
 *
 * Behaviour:
 *  - a plain re-open within [QuickPanelPolicy.RECENT_PANEL_MS] shows the
 *    outcome of the last panel send instead of a blank composer,
 *  - a ball double-tap opens the panel with the captured screen context
 *    attached (a chip above the composer); when accessibility is off, the
 *    composer is pre-filled with a generic "look at my screen" prompt.
 *
 * Manifest note: declared with `Theme.Minis.QuickPanel` (translucent), added
 * by the manifest owner — see the batch report for the exact XML.
 */
class QuickActivity : ComponentActivity() {

    private val prefs by lazy { QuickPrefs(this) }

    // Panel state. Plain `mutableStateOf` fields (not a ViewModel): the panel
    // is a transient surface; rotation restores nothing but the composer text
    // surviving in `pendingText` is acceptable for v1 — the reference behaves
    // the same way.
    private val composerText = mutableStateOf("")
    // [Wave3 #9] stagedImages removed with the unreachable ACTION_SEND path:
    // its only writer was handleSendMultiple (dead code), so the chip row and
    // the send()-attachment plumbing were dead state as well.
    private val runningSessionId = mutableStateOf<String?>(null)
    private val sending = mutableStateOf(false)

    // T-n01-andmenu-l10n: pre-Tiramisu locale override (same as
    // ShareReceiverActivity / MainActivity).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            // Same theme resolution as MainActivity: read the stored
            // appearance prefs once — no one-frame flash of the wrong theme.
            val appearance = remember { getAppearancePrefs(this) }
            val themeMode = appearance.getInt(KEY_THEME_MODE, 0)
            val fontScale = fontScaleForLevel(appearance.getInt(KEY_FONT_APP_BASE, 0))
            val dynamicColor = appearance.getBoolean(KEY_DYNAMIC_COLOR, false)
            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MinisTheme(darkTheme = darkTheme, fontScale = fontScale, dynamicColor = dynamicColor) {
                QuickPanel()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // The reuse window counts from the last time the panel was actually
        // seen, so re-opening repeatedly keeps the same session alive.
        prefs.touchPanelSession()
    }

    // ------------------------------------------------------------------ //
    // Intent handling                                                     //
    // ------------------------------------------------------------------ //

    // [Wave3 #9] The ACTION_SEND / ACTION_SEND_MULTIPLE branches are gone.
    // The manifest deliberately declares NO send filter for this activity
    // (AndroidManifest.xml: "Deliberately NO ACTION_SEND filter --
    // ShareReceiverActivity is the single share target"), so both handlers
    // were unreachable: QuickTileService starts this activity with an explicit
    // no-action intent and the launcher tile does the same. The share-sheet
    // flow lives in ShareReceiverActivity (T157 wildcard).
    private fun handleIntent(intent: Intent?) {
        handleSummon()
    }

    /**
     * Plain summon (tile / app). Reuse the last panel session inside the
     * window so the user sees where the previous prompt ran.
     */
    private fun handleSummon() {
        if (QuickPanelPolicy.shouldReusePanelSession(
                lastSessionId = prefs.panelSessionId,
                lastUsedAtMs = prefs.panelUsedAtMs,
                nowMs = System.currentTimeMillis(),
            )
        ) {
            runningSessionId.value = prefs.panelSessionId
        }
    }

    // ------------------------------------------------------------------ //
    // Sending                                                             //
    // ------------------------------------------------------------------ //

    private fun send() {
        if (sending.value) return
        val raw = composerText.value.trim()
        if (raw.isEmpty()) return
        sending.value = true
        val prompt: String = raw
        QuickPromptDispatcher.send(this, prompt, emptyList()) { sessionId ->
            sending.value = false
            if (sessionId == null) {
                // Could not start (no provider / half-built app): keep the
                // composer intact so the user can retry from the main app.
                toast(R.string.quick_send_failed)
            } else {
                runningSessionId.value = sessionId
                composerText.value = ""
                // The dispatcher also records this; the panel keeps its own
                // copy so the reopen window survives a quick close.
                prefs.markPanelSession(sessionId)
            }
        }
    }

    private fun openRunningSession() {
        val id = runningSessionId.value ?: return
        try {
            startActivity(QuickNotifications.openSessionIntent(this, id))
        } catch (t: Throwable) {
            AppLogger.warning("QuickActivity", "open session failed: ${t.message}")
        }
        finish()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ //
    // UI                                                                  //
    // ------------------------------------------------------------------ //

    @Composable
    private fun QuickPanel() {
        val colors = MaterialTheme.colorScheme
        // Tap-outside dismisses only when nothing would be lost.
        val dismissable = composerText.value.isBlank() && runningSessionId.value == null
        val dismissInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    enabled = dismissable,
                ) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    // Swallow taps on the card so they never reach the dismiss area.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                color = colors.surface,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.quick_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { finish() }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    }

                    runningSessionId.value?.let { RunningBanner(it) }

                    OutlinedTextField(
                        value = composerText.value,
                        onValueChange = { composerText.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        placeholder = { Text(stringResource(R.string.quick_panel_input_hint)) },
                        minLines = 1,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        shape = RoundedCornerShape(14.dp),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { finish() }) {
                            Text(stringResource(R.string.common_close))
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { send() },
                            enabled = !sending.value && composerText.value.isNotBlank(),
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.primary.copy(alpha = 0.12f)),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = stringResource(R.string.cd_send),
                                tint = if (sending.value) colors.onSurfaceVariant else colors.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RunningBanner(sessionId: String) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primary.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.quick_panel_running),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { openRunningSession() }) {
                Text(stringResource(R.string.quick_panel_open_session))
            }
        }
        // sessionId identifies the conversation the banner deep-links into;
        // the link itself is built in openRunningSession().
    }
}
