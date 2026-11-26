package com.ai.playtv.base.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Main-thread, page-owned scanner. Bounded 15-second scan, 100 latest device entries.
 * No scan starts in the constructor. Permission UI belongs to the host; API revocation is caught.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(BleScanSnapshot())
    val state = mutableState.asStateFlow()
    private var closed = false
    private var active: ScanRun? = null

    private class ScanRun(val scanner: android.bluetooth.le.BluetoothLeScanner) {
        lateinit var callback: ScanCallback
        lateinit var timeout: Runnable
        val devices = linkedMapOf<String, BlePeripheral>()
        var receiver: BroadcastReceiver? = null
        val pendingResults = AtomicInteger()
    }

    fun start() {
        mainThread()
        if (closed || active != null) return
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            fail(BleInspectionFailure.UNSUPPORTED); return
        }
        try {
            if (Build.VERSION.SDK_INT < 31) {
                val location = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (location == null || !LocationManagerCompat.isLocationEnabled(location)) {
                    fail(BleInspectionFailure.LOCATION_OFF); return
                }
            }
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) { fail(BleInspectionFailure.BLUETOOTH_OFF); return }
            val scanner = adapter.bluetoothLeScanner
                ?: run { fail(BleInspectionFailure.SCAN_REJECTED); return }
            val run = ScanRun(scanner)
            run.callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    // Scan observations may be coalesced/dropped under burst load; control events may not.
                    if (run.pendingResults.incrementAndGet() > 128) {
                        run.pendingResults.decrementAndGet()
                        return
                    }
                    handler.post {
                        try {
                            if (active !== run) return@post
                            val address = result.device.address
                            if (address !in run.devices && run.devices.size == 100) {
                                run.devices.remove(run.devices.keys.first())
                            }
                            run.devices[address] = BlePeripheral(address,
                                result.scanRecord?.deviceName ?: result.device.name, result.rssi)
                            mutableState.value = BleScanSnapshot(true, run.devices.values.toList())
                        } catch (_: SecurityException) { stopRun(run, BleInspectionFailure.PERMISSION) }
                        finally { run.pendingResults.decrementAndGet() }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post { stopRun(run, BleInspectionFailure.SCAN_REJECTED) }
                }
            }
            run.timeout = Runnable { stopRun(run, null) }
            active = run
            mutableState.value = BleScanSnapshot(scanning = true)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON)
                        stopRun(run, BleInspectionFailure.BLUETOOTH_OFF)
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED)
            run.receiver = receiver
            scanner.startScan(emptyList(), ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), run.callback)
            handler.postDelayed(run.timeout, 15_000L)
        } catch (_: SecurityException) {
            active?.let { stopRun(it, BleInspectionFailure.PERMISSION) } ?: fail(BleInspectionFailure.PERMISSION)
        } catch (_: RuntimeException) {
            active?.let { stopRun(it, BleInspectionFailure.SCAN_REJECTED) } ?: fail(BleInspectionFailure.SCAN_REJECTED)
        }
    }

    fun stop() { mainThread(); active?.let { stopRun(it, null) } }

    private fun stopRun(run: ScanRun, failure: BleInspectionFailure?) {
        mainThread()
        if (active !== run) return
        active = null // Invalidate before platform stop; late callbacks cannot restart this run.
        handler.removeCallbacks(run.timeout)
        var finalFailure = failure
        try { run.scanner.stopScan(run.callback) }
        catch (_: SecurityException) { finalFailure = finalFailure ?: BleInspectionFailure.PERMISSION }
        catch (_: RuntimeException) { finalFailure = finalFailure ?: BleInspectionFailure.SCAN_REJECTED }
        run.receiver?.let { try { app.unregisterReceiver(it) } catch (_: IllegalArgumentException) { } }
        run.receiver = null
        mutableState.value = BleScanSnapshot(false, run.devices.values.toList(), finalFailure)
    }
    private fun fail(reason: BleInspectionFailure) { mutableState.value = BleScanSnapshot(failure = reason) }
    override fun close() { mainThread(); if (closed) return; closed = true; stop(); handler.removeCallbacksAndMessages(null) }
    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper())
}
