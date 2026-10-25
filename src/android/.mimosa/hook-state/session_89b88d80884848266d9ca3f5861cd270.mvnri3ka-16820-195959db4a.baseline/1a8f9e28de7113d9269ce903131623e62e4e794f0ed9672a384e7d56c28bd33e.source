package com.openminis.app.data.repository

/**
 * Original OpenMinis-Linux skill — teaches the agent to drive the phone
 * through tools that already exist (PRoot, minis-su-cli, Shizuku, a11y,
 * notifications). Not a port of Operit / OmniBot.
 */
internal const val ANDROID_DEVICE_OPS_SKILL_ID = "android-device-ops"
internal const val ANDROID_DEVICE_OPS_SKILL_VERSION = "1.0.0"
internal val ANDROID_DEVICE_OPS_SKILL_CONTENT = """
---
name: android-device-ops
version: 1.0.0
description: Drive this Android phone through Minis Ultra tools — PRoot shell, minis-su-cli, Shizuku, accessibility, notifications — without LSPosed or bundled privileged APKs.
---

# Android device ops (Minis Ultra)

Use this skill when the user wants the agent to act on the **phone itself**
(settings, files outside the sandbox, notifications, UI taps, screenshots)
rather than only inside the Ubuntu PRoot workspace.

## Capability ladder (try in this order)

1. **PRoot Ubuntu (`shell_execute`)** — default. Isolated, no extra grant.
   Good for: packages, scripts, workspace files, network tools.
2. **`minis-su-cli`** — if the device is rooted and the user enabled the
   Host su path. Host-side `su -c`, not inside PRoot. Confirm the command
   before destructive actions (`reboot`, `rm`, `pm uninstall`).
3. **Shizuku** — if the user has already installed and authorized Shizuku
   (we do **not** ship a Shizuku APK). Prefer this over asking for root
   when a privileged Android API is enough.
4. **Accessibility service** — UI inspection / click / scroll / type when
   the user enabled Minis accessibility. Never enable it silently. If the
   service was killed by force-stop, tell the user to repair it in Settings.
5. **Notifications** — posting or reading via existing Minis notification
   helpers. Do not spam.

If a step is unavailable, **explain which grant is missing** and how the
user can turn it on. Do not pretend LSPosed / Xposed / system injection
exists — Minis Ultra will not implement those.

## Safety

- Quote the exact host command before `minis-su-cli` that mutates the device.
- Never dump or exfiltrate SMS, call logs, or photos unless the user asked
  in this turn.
- Prefer `web_search` for documentation, then `browser_use` only if a page
  must be clicked.
- Large command output is spilled to `/var/minis/workspace/tool-spill/` —
  `file_read` the spill file instead of re-running the command.

## Typical recipes

- "把这个 APK 装上": check install permission, then `pm install` via
  Shizuku/su, else walk the user through the system installer.
- "回到刚才的聊天": the overlay tap already deep-links `minis://session/<id>`.
- "在屏幕上点xxx": accessibility click; if a11y is off, stop and ask.
""".trimIndent()
