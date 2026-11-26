package com.ai.playtv.ui.test.bledemo

import androidx.lifecycle.viewModelScope
import com.ai.playtv.domain.BaseMviViewModel
import com.ai.playtv.base.bluetooth.ble.BleGattInspector
import com.ai.playtv.base.bluetooth.ble.BleScanner
import com.ai.playtv.model.contract.bledemo.BleDemoEffect
import com.ai.playtv.model.contract.bledemo.BleDemoEvent
import com.ai.playtv.model.contract.bledemo.BleDemoState
import com.ai.playtv.model.manager.bledemo.BleProtocolFixture
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Page-owned dependencies; no radio initialization in Application or global singleton. */
class BleDemoViewModel(
    private val scanner: BleScanner,
    private val inspector: BleGattInspector,
    private val fixture: BleProtocolFixture,
) : BaseMviViewModel<BleDemoState, BleDemoEvent, BleDemoEffect>(BleDemoState()) {
    private var fixtureJob: Job? = null
    private var fixtureGeneration = 0L
    init {
        viewModelScope.launch { scanner.state.collect { snapshot -> setState { copy(scan = snapshot) } } }
        viewModelScope.launch { inspector.state.collect { snapshot -> setState { copy(inspection = snapshot) } } }
    }

    override fun onEvent(event: BleDemoEvent) {
        when (event) {
            BleDemoEvent.Scan -> { inspector.disconnect(); scanner.start() }
            is BleDemoEvent.Connect -> {
                if (currentState.scan.devices.none { it.address == event.address }) return
                scanner.stop()
                inspector.connect(event.address)
            }
            BleDemoEvent.Stop -> {
                scanner.stop()
                inspector.disconnect()
                fixtureJob?.cancel()
            }
            is BleDemoEvent.RunFixture -> {
                if (fixtureJob?.isActive == true) return
                val generation = ++fixtureGeneration
                fixtureJob = viewModelScope.launch {
                    fixture.run(event.scenario) { snapshot ->
                        if (generation == fixtureGeneration) setState { copy(fixture = snapshot) }
                    }
                }
            }
        }
    }
    override fun onCleared() {
        fixtureGeneration++
        fixtureJob?.cancel()
        scanner.close()
        inspector.close()
        super.onCleared()
    }
}
