package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository

/**
 * [T-android-skill-copy-source-url] "Copy Source URL" on the Skills screens:
 * long-pressing a skill in the list, or its Source row on the detail page,
 * copies the URL it was installed from.
 *
 * The URL is `skills.source_url`, written by SkillRepository.importFromGitHub
 * (the URL tab of the import sheet, and Minis Ultra Skills). Nothing else records
 * one, so file / bundled / session skills offer no such action.
 */
internal object SkillSourceUrl {

    /**
     * The URL to offer, or null when this skill has none. Same condition as
     * SkillRepository.updateFromURL: imported from a URL, and the URL was
     * persisted (imports from before the column existed have none).
     */
    fun copyable(skill: SkillRepository.Skill): String? {
        if (skill.importSource != SkillRepository.ImportSource.URL) return null
        return skill.sourceURL?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun copy(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        // Android 13+ shows its own clipboard chip; the Toast covers < 13 and
        // names what was copied.
        Toast.makeText(context, context.getString(R.string.skill_source_url_copied), Toast.LENGTH_SHORT).show()
    }
}
