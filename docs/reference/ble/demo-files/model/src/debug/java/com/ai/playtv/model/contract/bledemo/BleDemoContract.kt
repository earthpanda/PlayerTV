package com.ai.playtv.model.contract.bledemo

import com.ai.playtv.base.bluetooth.ble.BleInspectionSnapshot
import com.ai.playtv.base.bluetooth.ble.BleScanSnapshot
import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import com.ai.playtv.model.state.bledemo.BleFixtureScenario
import com.ai.playtv.model.state.bledemo.BleFixtureSnapshot

data class BleDemoState(
    val scan: BleScanSnapshot = BleScanSnapshot(),
    val inspection: BleInspectionSnapshot = BleInspectionSnapshot(),
    val fixture: BleFixtureSnapshot = BleFixtureSnapshot(),
) : UiState

sealed interface BleDemoEvent : UiEvent {
    data object Scan : BleDemoEvent
    data class Connect(val address: String) : BleDemoEvent
    data object Stop : BleDemoEvent
    data class RunFixture(val scenario: BleFixtureScenario) : BleDemoEvent
}

sealed interface BleDemoEffect : UiEffect
