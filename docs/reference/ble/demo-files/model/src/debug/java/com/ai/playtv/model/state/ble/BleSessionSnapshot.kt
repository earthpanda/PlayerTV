package com.ai.playtv.model.state.ble

import com.ai.playtv.base.bluetooth.ble.transport.BleTransportFault
import com.ai.playtv.model.protocol.ble.BleDeviceInfo
import com.ai.playtv.model.protocol.ble.BleProtocolMessage
import com.ai.playtv.model.protocol.ble.BleSessionProblem

enum class BleSessionPhase { IDLE, CONNECTING, INITIALIZING, HANDSHAKING, READY, WAITING_RETRY, FAILED, CLOSED }
data class BleSessionSnapshot(
    val phase: BleSessionPhase = BleSessionPhase.IDLE,
    val generation: Long = 0,
    val retries: Int = 0,
    val info: BleDeviceInfo? = null,
    val problem: BleSessionProblem? = null,
    val transportFault: BleTransportFault? = null,
    val validatedFrames: Int = 0,
    val ignoredResponses: Int = 0,
)
data class BleSessionEvent(val generation: Long, val message: BleProtocolMessage.Event)
