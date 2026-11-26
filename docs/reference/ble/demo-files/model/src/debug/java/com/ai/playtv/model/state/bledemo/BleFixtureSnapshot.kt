package com.ai.playtv.model.state.bledemo

/** Explicit local fixture states; never presented as observations of a Bluetooth peripheral. */
enum class BleFixtureScenario { ROUND_TRIP, RECONNECT, MISSING_CALLBACK, STALE_CALLBACK }
enum class BleFixturePhase { IDLE, CONNECTING, SUBSCRIBING, HANDSHAKING, WRITING, WAITING_RESPONSE, WAITING_RETRY, COMPLETED, TIMED_OUT, CANCELLED, FAILED }
data class BleFixtureSnapshot(
    val phase: BleFixturePhase = BleFixturePhase.IDLE,
    val busy: Boolean = false,
    val connectionGeneration: Long = 0,
    val retries: Int = 0,
    val validatedFrames: Int = 0,
    val ignoredLateCallbacks: Int = 0,
)
