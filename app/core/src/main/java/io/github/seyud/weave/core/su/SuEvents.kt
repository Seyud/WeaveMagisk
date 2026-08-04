package io.github.seyud.weave.core.su

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object SuEvents {
    private val _policyChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val policyChanged = _policyChanged.asSharedFlow()

    fun notifyPolicyChanged() {
        _policyChanged.tryEmit(Unit)
    }
}
