package com.ai.playtv.base.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Real GATT connection and service inventory only. No CCCD, vendor commands or pairing automation.
 * One active connection, explicit main-thread operations, new identity for every attempt (same MAC too).
 * Each phase is bounded; close does not depend on a later Android disconnect callback.
 */
@SuppressLint("MissingPermission")
class BleGattInspector(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(BleInspectionSnapshot())
    val state = mutableState.asStateFlow()
    private var active: Connection? = null
    private var closed = false

    private class Connection(val address: String) {
        var gatt: BluetoothGatt? = null
        var deadline: Runnable? = null
        var receiver: BroadcastReceiver? = null
    }

    fun connect(address: String) {
        mainThread()
        if (closed) return
        if (active?.address == address) return
        disconnect()
        val session = Connection(address)
        active = session
        mutableState.value = BleInspectionSnapshot(address, BleInspectionPhase.CONNECTING)
        try {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) { retire(session, BleInspectionFailure.BLUETOOTH_OFF); return }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON)
                        retire(session, BleInspectionFailure.BLUETOOTH_OFF)
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED)
            session.receiver = receiver
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    handler.post {
                        if (!current(session, gatt)) return@post
                        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                            retire(session, BleInspectionFailure.LINK_FAILED); return@post
                        }
                        if (newState != BluetoothProfile.STATE_CONNECTED ||
                            mutableState.value.phase != BleInspectionPhase.CONNECTING) return@post
                        mutableState.value = BleInspectionSnapshot(address, BleInspectionPhase.DISCOVERING)
                        armDeadline(session, 10_000L)
                        try {
                            if (!gatt.discoverServices()) retire(session, BleInspectionFailure.DISCOVERY_FAILED)
                        } catch (_: SecurityException) { retire(session, BleInspectionFailure.PERMISSION) }
                        catch (_: RuntimeException) { retire(session, BleInspectionFailure.DISCOVERY_FAILED) }
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    handler.post {
                        if (!current(session, gatt) || mutableState.value.phase != BleInspectionPhase.DISCOVERING) return@post
                        if (status != BluetoothGatt.GATT_SUCCESS) { retire(session, BleInspectionFailure.DISCOVERY_FAILED); return@post }
                        try {
                            val services = gatt.services.map { service ->
                                BleServiceInfo(service.uuid.toString(), service.instanceId,
                                    service.characteristics.map { characteristic ->
                                        BleCharacteristicInfo(characteristic.uuid.toString(), characteristic.properties,
                                            characteristic.descriptors.map { it.uuid.toString() })
                                    })
                            }
                            session.deadline?.let(handler::removeCallbacks)
                            session.deadline = null
                            mutableState.value = BleInspectionSnapshot(address, BleInspectionPhase.DISCOVERED, services)
                        } catch (_: SecurityException) { retire(session, BleInspectionFailure.PERMISSION) }
                        catch (_: RuntimeException) { retire(session, BleInspectionFailure.DISCOVERY_FAILED) }
                    }
                }
            }
            // Callback work is posted to the owner, so the returned handle is bound before use.
            session.gatt = adapter.getRemoteDevice(address).connectGatt(app, false, callback,
                BluetoothDevice.TRANSPORT_LE)
            if (session.gatt == null) retire(session, BleInspectionFailure.CONNECT_REJECTED)
            else armDeadline(session, 15_000L)
        } catch (_: SecurityException) { retire(session, BleInspectionFailure.PERMISSION) }
        catch (_: RuntimeException) { retire(session, BleInspectionFailure.CONNECT_REJECTED) }
    }

    fun disconnect() { mainThread(); active?.let { retire(it, null) } }
    private fun current(session: Connection, gatt: BluetoothGatt) = active === session && session.gatt === gatt
    private fun armDeadline(session: Connection, timeoutMs: Long) {
        session.deadline?.let(handler::removeCallbacks)
        session.deadline = Runnable { retire(session, BleInspectionFailure.TIMEOUT) }
            .also { handler.postDelayed(it, timeoutMs) }
    }
    private fun retire(session: Connection, failure: BleInspectionFailure?) {
        mainThread()
        if (active !== session) return
        active = null
        session.deadline?.let(handler::removeCallbacks)
        session.deadline = null
        session.receiver?.let { try { app.unregisterReceiver(it) } catch (_: IllegalArgumentException) { } }
        session.receiver = null
        val gatt = session.gatt
        session.gatt = null
        var finalFailure = failure
        try { gatt?.disconnect() } catch (_: SecurityException) { finalFailure = finalFailure ?: BleInspectionFailure.PERMISSION }
        catch (_: RuntimeException) { finalFailure = finalFailure ?: BleInspectionFailure.LINK_FAILED }
        try { gatt?.close() } catch (_: RuntimeException) { finalFailure = finalFailure ?: BleInspectionFailure.LINK_FAILED }
        mutableState.value = BleInspectionSnapshot(session.address,
            if (finalFailure == null) BleInspectionPhase.DISCONNECTED else BleInspectionPhase.FAILED,
            failure = finalFailure)
    }
    override fun close() { mainThread(); if (closed) return; closed = true; disconnect(); handler.removeCallbacksAndMessages(null) }
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
