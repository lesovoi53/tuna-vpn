package com.wireturn.app

import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceState {
    @Volatile var generation: Long = 0
        private set
    @Volatile var tunEstablished: Boolean = false
        internal set
    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state = _state.asStateFlow()

    fun updateStatus(newStatus: VpnState) {
        if (newStatus == VpnState.Running && _state.value != VpnState.Running) generation++
        _state.value = newStatus
    }
}
