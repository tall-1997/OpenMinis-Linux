package com.openminis.app.ui.sessions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [T-android-sessionlist-prefs-io] Collapsed-folder persistence for
 * [SessionListViewModel], extracted from the VM body to keep the VM under its
 * architecture-policy line ceiling. The VM's [SessionListViewModel.uiPrefs]
 * is internal for exactly this reason. Deliberately OUTSIDE the safe-mode
 * await — folder collapse state has nothing to do with the crash gate and
 * must not wait for the user to dismiss the share dialog.
 */
internal fun SessionListViewModel.loadCollapsedFolderIds() {
    viewModelScope.launch(Dispatchers.IO) {
        val ids = uiPrefs.getStringSet("collapsedFolderIds", emptySet())?.toSet() ?: emptySet()
        collapsedFolderIds.value = ids
    }
}

internal fun SessionListViewModel.setCollapsedFolders(ids: Set<String>) {
    collapsedFolderIds.value = ids
    uiPrefs.edit().putStringSet("collapsedFolderIds", ids).apply()
}
