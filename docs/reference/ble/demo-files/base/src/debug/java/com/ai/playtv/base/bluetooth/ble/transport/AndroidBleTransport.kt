package com.ai.playtv.base.bluetooth.ble.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Optional platform reference. Construct/call/collect/close on Main; permissions belong to host.
 * One connection owns ONE bounded queue for discovery, MTU, CCCD, read and write operations.
 * No hidden refresh, bonding, command replay or protocol-specific fragmentation.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    context: Context,
    private val connectTimeoutMs: Long = 15_000,
    private val operationTimeoutMs: Long = 5_000,
) : BleTransport {
    private val context = context.applicationContext
    init { require(connectTimeoutMs > 0 && operationTimeoutMs > 0) }

    override suspend fun open(address: String, profile: BleGattProfile, progress: (BleLinkStage) -> Unit): BleWireConnection {
        checkMain()
        currentCoroutineContext().ensureActive()
        val link = Link(profile)
        try {
            progress(BleLinkStage.CONNECTING)
            link.connect(address)
            progress(BleLinkStage.DISCOVERING)
            link.discover()
            link.validateProfile()
            progress(BleLinkStage.NEGOTIATING_MTU)
            link.negotiateMtu()
            progress(BleLinkStage.SUBSCRIBING)
            link.subscribe()
            return link
        } catch (cancelled: CancellationException) {
            link.close()
            throw cancelled
        } catch (failure: Exception) {
            link.close()
            throw if (failure is SecurityException) BleTransportException(BleTransportFault.PERMISSION, failure) else failure
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "BLE owner must be Main" }

    private enum class Kind { DISCOVERY, MTU, DESCRIPTOR, READ, WRITE }
    private data class Result(val status: Int, val bytes: ByteArray = byteArrayOf(), val mtu: Int = 23)
    private class Pending(val kind: Kind, val target: Any?, val reply: CompletableDeferred<Result> = CompletableDeferred())

    private inner class Link(private val profile: BleGattProfile) : BleWireConnection {
        private val handler = Handler(Looper.getMainLooper())
        private val ingress = AtomicInteger()
        private val overflowPosted = AtomicBoolean()
        private val queue = Mutex()
        private val admissions = Semaphore(8)
        private val connected = CompletableDeferred<Unit>()
        private val channel = Channel<BleNotification>(64)
        override val notifications = channel.receiveAsFlow()
        private var gatt: BluetoothGatt? = null
        @Volatile private var closed = false
        private var terminal: BleTransportException? = null
        private var receiverRegistered = false
        private var pending: Pending? = null
        private var mtu = 23
        private val endpoints = mutableMapOf<BleEndpoint, BluetoothGattCharacteristic>()
        override val isOpen: Boolean get() = !closed && gatt != null
        override val maxWriteBytes: Int get() = minOf(mtu - 3, 512, profile.maxWriteBytes)

        // A callback belongs to the original GATT, never just to an address or current singleton.
        // Bounded callback ingress avoids an unlimited Handler backlog while Main is busy.
        private fun post(original: BluetoothGatt, action: () -> Unit) {
            if (closed) return
            if (ingress.incrementAndGet() > 64) {
                ingress.decrementAndGet()
                if (overflowPosted.compareAndSet(false, true)) handler.post { if (!closed && gatt === original) fail(BleTransportFault.OVERFLOW) }
                return
            }
            handler.post {
                ingress.decrementAndGet()
                if (!closed && gatt === original) action()
            }
        }

        private fun complete(kind: Kind, target: Any?, result: Result) {
            val turn = pending ?: return
            if (turn.kind == kind && turn.target === target) turn.reply.complete(result)
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = post(g) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> fail(BleTransportFault.LINK_LOST)
                    newState == BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> fail(BleTransportFault.LINK_LOST)
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = post(g) {
                complete(Kind.DISCOVERY, null, Result(status))
            }
            override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) = post(g) {
                // Android 14 may negotiate 517 irrespective of the requested value.
                if (status == BluetoothGatt.GATT_SUCCESS && value in 23..517) mtu = value
                complete(Kind.MTU, null, Result(status, mtu = value))
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = post(g) {
                complete(Kind.DESCRIPTOR, d, Result(status))
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = post(g) {
                complete(Kind.WRITE, c, Result(status))
            }
            @Deprecated("Legacy callback")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) {
                    val bytes = c.value?.copyOf() ?: byteArrayOf()
                    post(g) { complete(Kind.READ, c, Result(status, bytes)) }
                }
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                val bytes = value.copyOf()
                post(g) { complete(Kind.READ, c, Result(status, bytes)) }
            }
            @Deprecated("Legacy callback")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) notification(g, c, c.value?.copyOf() ?: byteArrayOf())
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                notification(g, c, value.copyOf())
            }
        }
        private fun notification(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) = post(g) {
            val endpoint = endpoints.entries.firstOrNull { it.value === characteristic }?.key
            if (endpoint != null && profile.subscriptions.any { it.endpoint == endpoint }) {
                if (bytes.size > 512 || !channel.trySend(BleNotification(endpoint, bytes)).isSuccess) fail(BleTransportFault.OVERFLOW)
            }
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) fail(BleTransportFault.BLUETOOTH_OFF)
            }
        }

        suspend fun connect(address: String) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw BleTransportException(BleTransportFault.BLUETOOTH_OFF)
            if (!adapter.isEnabled) throw BleTransportException(BleTransportFault.BLUETOOTH_OFF)
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
            receiverRegistered = true
            // Callbacks always post; returned handle is bound before they are processed.
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleTransportException(BleTransportFault.REJECTED)
            withTimeoutOrNull(connectTimeoutMs) { connected.await(); true }
                ?: throw BleTransportException(BleTransportFault.CONNECT_TIMEOUT)
        }
        private fun requireOpen(): BluetoothGatt {
            checkMain()
            if (closed) throw terminal ?: BleTransportException(BleTransportFault.CLOSED)
            return gatt ?: throw BleTransportException(BleTransportFault.CLOSED)
        }

        /** Slot is held through API return AND matching callback. Accepted timeout/cancellation
         * retires the whole connection: a late callback must never complete the next operation.
         */
        private suspend fun operation(kind: Kind, target: Any? = null, invoke: (BluetoothGatt) -> Boolean): Result {
            requireOpen()
            if (!admissions.tryAcquire()) {
                fail(BleTransportFault.OVERFLOW)
                throw BleTransportException(BleTransportFault.OVERFLOW)
            }
            try {
                return queue.withLock {
                    val original = requireOpen()
                    currentCoroutineContext().ensureActive()
                    val turn = Pending(kind, target)
                    pending = turn
                    try {
                        if (!invoke(original)) throw BleTransportException(BleTransportFault.REJECTED)
                        withTimeoutOrNull(operationTimeoutMs) { turn.reply.await() } ?: run {
                            fail(BleTransportFault.OPERATION_TIMEOUT)
                            throw terminal!!
                        }
                    } catch (cancelled: CancellationException) {
                        close()
                        throw cancelled
                    } catch (failure: SecurityException) {
                        fail(BleTransportFault.PERMISSION)
                        throw BleTransportException(BleTransportFault.PERMISSION, failure)
                    } finally { if (pending === turn) pending = null }
                }
            } finally { admissions.release() }
        }
        private fun Result.checked(): Result {
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleTransportException(BleTransportFault.CALLBACK_FAILED)
            return this
        }
        suspend fun discover() { operation(Kind.DISCOVERY) { it.discoverServices() }.checked() }
        fun validateProfile() {
            val original = requireOpen()
            val required = (listOf(profile.writeEndpoint) + profile.readableEndpoints + profile.subscriptions.map { it.endpoint }).distinct()
            for (endpoint in required) {
                val services = original.services.filter { it.uuid == endpoint.service }
                val characteristics = services.singleOrNull()?.characteristics?.filter { it.uuid == endpoint.characteristic }
                endpoints[endpoint] = characteristics?.singleOrNull() ?: throw BleTransportException(BleTransportFault.PROFILE_MISMATCH)
            }
            fun has(endpoint: BleEndpoint, property: Int) = endpoints.getValue(endpoint).properties and property != 0
            val property = if (profile.writeMode == BleWriteMode.WITH_RESPONSE) BluetoothGattCharacteristic.PROPERTY_WRITE else BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            if (!has(profile.writeEndpoint, property) || profile.readableEndpoints.any { !has(it, BluetoothGattCharacteristic.PROPERTY_READ) })
                throw BleTransportException(BleTransportFault.PROFILE_MISMATCH)
            profile.subscriptions.forEach {
                val bit = if (it.mode == BleSubscriptionMode.NOTIFY) BluetoothGattCharacteristic.PROPERTY_NOTIFY else BluetoothGattCharacteristic.PROPERTY_INDICATE
                if (!has(it.endpoint, bit)) throw BleTransportException(BleTransportFault.PROFILE_MISMATCH)
            }
        }
        suspend fun negotiateMtu() {
            profile.requestedMtu?.let { requested ->
                try {
                    val result = operation(Kind.MTU) { it.requestMtu(requested) }.checked()
                    if (result.mtu !in 23..517) throw BleTransportException(BleTransportFault.CALLBACK_FAILED)
                } catch (failure: BleTransportException) {
                    // Only a definitive rejection/failure permits fallback; missing callback retires.
                    if (!profile.allowMtuFallback || failure.fault !in setOf(BleTransportFault.REJECTED, BleTransportFault.CALLBACK_FAILED)) throw failure
                }
            }
            if (mtu < profile.minimumMtu) throw BleTransportException(BleTransportFault.PROFILE_MISMATCH)
        }
        suspend fun subscribe() {
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            for (subscription in profile.subscriptions) {
                val c = endpoints.getValue(subscription.endpoint)
                val d = c.descriptors.filter { it.uuid == cccdUuid }.singleOrNull()
                    ?: throw BleTransportException(BleTransportFault.PROFILE_MISMATCH)
                val bytes = if (subscription.mode == BleSubscriptionMode.NOTIFY) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.copyOf()
                operation(Kind.DESCRIPTOR, d) { original ->
                    original.setCharacteristicNotification(c, true) && if (Build.VERSION.SDK_INT >= 33)
                        original.writeDescriptor(d, bytes) == BluetoothStatusCodes.SUCCESS
                    else { d.value = bytes; original.writeDescriptor(d) }
                }.checked()
            }
        }
        override suspend fun write(packet: ByteArray) {
            requireOpen()
            require(packet.isNotEmpty() && packet.size <= maxWriteBytes)
            val bytes = packet.copyOf()
            val c = endpoints.getValue(profile.writeEndpoint)
            val type = if (profile.writeMode == BleWriteMode.WITH_RESPONSE) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            operation(Kind.WRITE, c) {
                if (Build.VERSION.SDK_INT >= 33) it.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
                else { c.writeType = type; c.value = bytes; it.writeCharacteristic(c) }
            }.checked()
        }
        override suspend fun read(endpoint: BleEndpoint): ByteArray {
            requireOpen()
            require(endpoint in profile.readableEndpoints)
            val c = endpoints.getValue(endpoint)
            return operation(Kind.READ, c) { it.readCharacteristic(c) }.checked().bytes
        }
        private fun fail(fault: BleTransportFault) { if (!closed) { terminal = BleTransportException(fault); close() } }
        override fun close() {
            checkMain()
            if (closed) return
            closed = true
            val failure = terminal ?: BleTransportException(BleTransportFault.CLOSED)
            connected.completeExceptionally(failure)
            pending?.reply?.completeExceptionally(failure)
            pending = null
            channel.close(failure)
            handler.removeCallbacksAndMessages(null)
            if (receiverRegistered) {
                receiverRegistered = false
                runCatching { context.unregisterReceiver(receiver) }
            }
            val original = gatt
            gatt = null
            endpoints.clear()
            runCatching { original?.disconnect() }
            runCatching { original?.close() }
        }
    }
}
