package io.github.seyud.weave.ui.module.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.seyud.weave.ui.component.SearchStatus

@Stable
internal class ModuleScreenLocalState internal constructor(
    private val searchStatusState: MutableState<SearchStatus>,
    private val showTopPopupState: MutableState<Boolean>,
    private val showShortcutDialogState: MutableState<Boolean>,
    private val showShortcutTypeDialogState: MutableState<Boolean>,
) {
    var searchStatus: SearchStatus
        get() = searchStatusState.value
        set(value) {
            searchStatusState.value = value
        }

    var showTopPopup: Boolean
        get() = showTopPopupState.value
        set(value) {
            showTopPopupState.value = value
        }

    var showShortcutDialog: Boolean
        get() = showShortcutDialogState.value
        set(value) {
            showShortcutDialogState.value = value
        }

    var showShortcutTypeDialog: Boolean
        get() = showShortcutTypeDialogState.value
        set(value) {
            showShortcutTypeDialogState.value = value
        }
}

@Composable
internal fun rememberModuleScreenLocalState(
    searchModulesLabel: String,
): ModuleScreenLocalState {
    val searchStatusState = remember(searchModulesLabel) {
        mutableStateOf(SearchStatus(label = searchModulesLabel))
    }
    val showTopPopupState = remember { mutableStateOf(false) }
    val showShortcutDialogState = rememberSaveable { mutableStateOf(false) }
    val showShortcutTypeDialogState = rememberSaveable { mutableStateOf(false) }

    return remember(
        searchStatusState,
        showTopPopupState,
        showShortcutDialogState,
        showShortcutTypeDialogState,
    ) {
        ModuleScreenLocalState(
            searchStatusState = searchStatusState,
            showTopPopupState = showTopPopupState,
            showShortcutDialogState = showShortcutDialogState,
            showShortcutTypeDialogState = showShortcutTypeDialogState,
        )
    }
}
