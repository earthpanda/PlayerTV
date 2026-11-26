package com.ai.playtv.ui.test.bledemo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModelProvider
import com.ai.playtv.R
import com.ai.playtv.databinding.ActivityBleDemoBinding
import com.ai.playtv.di.viewModelFactory
import com.ai.playtv.ui.BaseMviActivity
import com.ai.playtv.base.bluetooth.ble.BleGattInspector
import com.ai.playtv.base.bluetooth.ble.BleInspectionFailure
import com.ai.playtv.base.bluetooth.ble.BleInspectionPhase
import com.ai.playtv.base.bluetooth.ble.BleScanner
import com.ai.playtv.base.permission.PermissionActionController
import com.ai.playtv.base.ui.SystemBarInsets
import com.ai.playtv.base.ui.message.MessageController
import com.ai.playtv.model.contract.bledemo.BleDemoEffect
import com.ai.playtv.model.contract.bledemo.BleDemoEvent
import com.ai.playtv.model.contract.bledemo.BleDemoState
import com.ai.playtv.model.manager.bledemo.BleProtocolFixture
import com.ai.playtv.model.state.bledemo.BleFixturePhase
import com.ai.playtv.model.state.bledemo.BleFixtureScenario

/** Debug generic BLE inspector + visibly separate local protocol fixture. No unknown-device writes. */
class BleDemoActivity : BaseMviActivity<ActivityBleDemoBinding, BleDemoState, BleDemoEvent,
    BleDemoEffect, BleDemoViewModel>(ActivityBleDemoBinding::inflate) {
    override val viewModelClass = BleDemoViewModel::class.java
    override fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory(viewModelClass) {
        BleDemoViewModel(BleScanner(applicationContext), BleGattInspector(applicationContext), BleProtocolFixture())
    }
    private lateinit var permissions: PermissionActionController
    private lateinit var messages: MessageController
    private lateinit var devices: BlePeripheralAdapter
    private var entryPermissionAttempted = false

    override fun initView() {
        permissions = PermissionActionController(this)
        messages = MessageController(this)
        devices = BlePeripheralAdapter { address ->
            requestAccess { viewModel.onEvent(BleDemoEvent.Connect(address)) }
        }
        binding.devices.adapter = devices
        binding.scan.setOnClickListener { requestAccess { viewModel.onEvent(BleDemoEvent.Scan) } }
        binding.stop.setOnClickListener { permissions.cancelPending(); viewModel.onEvent(BleDemoEvent.Stop) }
        binding.radioSettings.setOnClickListener {
            permissions.cancelPending()
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            catch (_: RuntimeException) { messages.show(getString(R.string.s_ble_demo_settings_failed)) }
        }
        binding.roundTrip.setOnClickListener { runFixture(BleFixtureScenario.ROUND_TRIP) }
        binding.reconnect.setOnClickListener { runFixture(BleFixtureScenario.RECONNECT) }
        binding.timeout.setOnClickListener { runFixture(BleFixtureScenario.MISSING_CALLBACK) }
        binding.stale.setOnClickListener { runFixture(BleFixtureScenario.STALE_CALLBACK) }
    }

    override fun onResume() {
        super.onResume()
        if (!entryPermissionAttempted) {
            entryPermissionAttempted = true
            requestAccess { } // Entering prepares permissions only; scan/connect remain explicit.
        }
    }
    private fun requestAccess(onGranted: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.request(required, getString(R.string.s_ble_demo_permission_denied),
            getString(R.string.s_ble_demo_permission_settings), onGranted)
    }
    private fun runFixture(scenario: BleFixtureScenario) {
        // Local fixture is independent of runtime Bluetooth permission and adapter power.
        viewModel.onEvent(BleDemoEvent.RunFixture(scenario))
    }
    override fun render(state: BleDemoState) {
        devices.submitList(state.scan.devices)
        binding.scan.isEnabled = !state.scan.scanning
        binding.scanStatus.text = getString(
            if (state.scan.scanning) R.string.s_ble_demo_scanning else R.string.s_ble_demo_scan_stopped,
            state.scan.devices.size)
        val phase = when (state.inspection.phase) {
            BleInspectionPhase.IDLE -> R.string.s_ble_demo_idle
            BleInspectionPhase.CONNECTING -> R.string.s_ble_demo_connecting
            BleInspectionPhase.DISCOVERING -> R.string.s_ble_demo_discovering
            BleInspectionPhase.DISCOVERED -> R.string.s_ble_demo_discovered
            BleInspectionPhase.DISCONNECTED -> R.string.s_ble_demo_disconnected
            BleInspectionPhase.FAILED -> R.string.s_ble_demo_failed
        }
        binding.connectionStatus.text = getString(phase)
        val failure = state.inspection.failure ?: state.scan.failure.takeIf {
            state.inspection.phase == BleInspectionPhase.IDLE || state.inspection.phase == BleInspectionPhase.DISCONNECTED
        }
        binding.error.text = failure?.let { getString(failureText(it)) }.orEmpty()
        binding.services.text = state.inspection.services.joinToString("\n") { service ->
            getString(R.string.s_ble_demo_service, service.uuid, service.instanceId) + "\n" +
                service.characteristics.joinToString("\n") { characteristic ->
                    getString(R.string.s_ble_demo_characteristic, characteristic.uuid,
                        characteristic.properties, characteristic.descriptors.joinToString())
                }
        }
        val fixture = state.fixture
        val fixturePhase = when (fixture.phase) {
            BleFixturePhase.IDLE -> R.string.s_ble_demo_idle
            BleFixturePhase.CONNECTING -> R.string.s_ble_demo_connecting
            BleFixturePhase.SUBSCRIBING -> R.string.s_ble_demo_fixture_subscribing
            BleFixturePhase.HANDSHAKING -> R.string.s_ble_demo_fixture_handshaking
            BleFixturePhase.WRITING -> R.string.s_ble_demo_fixture_writing
            BleFixturePhase.WAITING_RESPONSE -> R.string.s_ble_demo_fixture_response
            BleFixturePhase.WAITING_RETRY -> R.string.s_ble_demo_fixture_retry
            BleFixturePhase.COMPLETED -> R.string.s_ble_demo_fixture_completed
            BleFixturePhase.TIMED_OUT -> R.string.s_ble_demo_fixture_timed_out
            BleFixturePhase.CANCELLED -> R.string.s_ble_demo_fixture_cancelled
            BleFixturePhase.FAILED -> R.string.s_ble_demo_failed
        }
        binding.fixtureStatus.text = getString(R.string.s_ble_demo_fixture_state,
            getString(fixturePhase), fixture.connectionGeneration, fixture.retries,
            fixture.validatedFrames, fixture.ignoredLateCallbacks)
        listOf(binding.roundTrip, binding.reconnect, binding.timeout, binding.stale).forEach { it.isEnabled = !fixture.busy }
    }
    private fun failureText(failure: BleInspectionFailure): Int = when (failure) {
        BleInspectionFailure.UNSUPPORTED -> R.string.s_ble_demo_unsupported
        BleInspectionFailure.BLUETOOTH_OFF -> R.string.s_ble_demo_radio_off
        BleInspectionFailure.LOCATION_OFF -> R.string.s_ble_demo_location_off
        BleInspectionFailure.PERMISSION -> R.string.s_ble_demo_permission_denied
        BleInspectionFailure.SCAN_REJECTED -> R.string.s_ble_demo_scan_failed
        BleInspectionFailure.CONNECT_REJECTED -> R.string.s_ble_demo_connect_failed
        BleInspectionFailure.LINK_FAILED -> R.string.s_ble_demo_link_failed
        BleInspectionFailure.DISCOVERY_FAILED -> R.string.s_ble_demo_discovery_failed
        BleInspectionFailure.TIMEOUT -> R.string.s_ble_demo_deadline
    }
    override fun handleEffect(effect: BleDemoEffect) = Unit
    override fun onStop() {
        // This inspector has no background owner. Permission/settings/rotation never resume old work.
        if (::permissions.isInitialized) permissions.cancelPending()
        viewModel.onEvent(BleDemoEvent.Stop)
        super.onStop()
    }
    override fun onDestroy() {
        if (::permissions.isInitialized) permissions.close()
        if (::messages.isInitialized) messages.close()
        binding.devices.adapter = null
        listOf(binding.scan, binding.stop, binding.radioSettings, binding.roundTrip,
            binding.reconnect, binding.timeout, binding.stale).forEach { it.setOnClickListener(null) }
        SystemBarInsets.clear(binding.root)
        super.onDestroy()
    }
}
