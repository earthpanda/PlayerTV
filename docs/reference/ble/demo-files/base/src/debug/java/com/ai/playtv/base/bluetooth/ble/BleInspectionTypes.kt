package com.ai.playtv.base.bluetooth.ble

/** Platform inspection facts only; DISCOVERED does not claim protocol readiness. */
enum class BleInspectionPhase { IDLE, CONNECTING, DISCOVERING, DISCOVERED, DISCONNECTED, FAILED }
enum class BleInspectionFailure { UNSUPPORTED, BLUETOOTH_OFF, LOCATION_OFF, PERMISSION, SCAN_REJECTED, CONNECT_REJECTED, LINK_FAILED, DISCOVERY_FAILED, TIMEOUT }
data class BlePeripheral(val address: String, val name: String?, val rssi: Int)
data class BleScanSnapshot(
    val scanning: Boolean = false,
    val devices: List<BlePeripheral> = emptyList(),
    val failure: BleInspectionFailure? = null,
)
data class BleCharacteristicInfo(val uuid: String, val properties: Int, val descriptors: List<String>)
data class BleServiceInfo(val uuid: String, val instanceId: Int, val characteristics: List<BleCharacteristicInfo>)
data class BleInspectionSnapshot(
    val address: String? = null,
    val phase: BleInspectionPhase = BleInspectionPhase.IDLE,
    val services: List<BleServiceInfo> = emptyList(),
    val failure: BleInspectionFailure? = null,
)
