package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository

/**
 * [OpenMinis#267] Localized display name for BUILT-IN (bundled) skills.
 *
 * A skill's `name` comes from its SKILL.md frontmatter, which for the bundled
 * skills is English ("skill-creator", "android-sdk-mirrors"). The management
 * list and the session-skills sheet rendered that raw value, so a Chinese UI
 * showed English skill names. User-imported skills must keep their own name
 * verbatim (it is their identity and what `invoke_skill` matches), so this
 * mapping is deliberately limited to the skills this app ships.
 */
@Composable
fun localizedSkillName(skill: SkillRepository.Skill): String = when {
    skill.id == SKILL_CREATOR_ID || skill.name.equals(SKILL_CREATOR_ID, ignoreCase = true) ->
        stringResource(R.string.skill_display_name_skill_creator)
    skill.id == ANDROID_SDK_MIRRORS_ID ||
        skill.name.equals(ANDROID_SDK_MIRRORS_ID, ignoreCase = true) ->
        stringResource(R.string.skill_display_name_android_sdk_mirrors)
    else -> skill.name
}

private const val SKILL_CREATOR_ID = "skill-creator"
private const val ANDROID_SDK_MIRRORS_ID = "android-sdk-mirrors"
