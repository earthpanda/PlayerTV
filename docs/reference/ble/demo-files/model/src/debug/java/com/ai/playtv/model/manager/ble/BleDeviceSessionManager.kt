package com.ai.playtv.model.manager.ble

import com.ai.playtv.base.bluetooth.ble.frame.BleFrameAssembler
import com.ai.playtv.base.bluetooth.ble.retry.BleDisconnectCause
import com.ai.playtv.base.bluetooth.ble.retry.BleReconnectPolicy
import com.ai.playtv.base.bluetooth.ble.transport.*
import com.ai.playtv.model.protocol.ble.*
import com.ai.playtv.model.state.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** Connection intent + request ownership. All entry points and parentScope must use the same
 * serial Main thread (transport contract); no global scope, Activity, automatic command replay.
 * Parent cancellation closes resources. close cancels only this manager's child job.
 */
class BleDeviceSessionManager(
    parentScope: CoroutineScope,
    private val transport: BleTransport,
    private val protocol: BleDeviceProtocol,
    private val reconnectPolicy: BleReconnectPolicy,
    private val requestTimeoutMs: Long = 10_000,
    private val handshakeTimeoutMs: Long = 15_000,
    private val stableReadyMs: Long = 30_000,
) : AutoCloseable {
    init {
        require(requestTimeoutMs > 0 && handshakeTimeoutMs > 0 && stableReadyMs > 0)
        requireNotNull(parentScope.coroutineContext[Job]) { "BLE needs a lifecycle owner Job" }
    }
    private val ownerThread = Thread.currentThread()
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val mutableState = MutableStateFlow(BleSessionSnapshot())
    val state: StateFlow<BleSessionSnapshot> = mutableState.asStateFlow()
    private val eventQueue = Channel<BleSessionEvent>(32)
    /** One consumer; generation is included so downstream suspended work can recheck ownership. */
    val events: Flow<BleSessionEvent> = eventQueue.receiveAsFlow().filter {
        it.generation == state.value.generation && state.value.phase == BleSessionPhase.READY
    }
    private var lifecycle: Job? = null
    private var active: Link? = null
    private var intent = 0L
    private var generation = 0L
    private var closed = false

    private class Waiting(val key: BleResponseKey, val reply: CompletableDeferred<BleProtocolMessage.Response> = CompletableDeferred())
    private class Link(val generation: Long, val wire: BleWireConnection, val codec: BleProtocolSession) {
        val failure = CompletableDeferred<Exception>()
        val mutex = Mutex()
        val admissions = Semaphore(8)
        var nextId = codec.requestIds.first.toLong()
        var waiting: Waiting? = null
    }
    private fun checkOwner() = check(Thread.currentThread() === ownerThread) { "BLE owner thread changed" }
    private fun update(change: (BleSessionSnapshot) -> BleSessionSnapshot) { mutableState.value = change(state.value) }
    private fun requireCurrent(link: Link) {
        checkOwner()
        if (active !== link || !link.wire.isOpen || link.failure.isCompleted)
            throw BleProtocolException(BleSessionProblem.CLOSED)
    }
    private fun abort(link: Link, failure: Exception) {
        if (active !== link) return
        link.failure.complete(failure)
        link.waiting?.reply?.completeExceptionally(failure)
        link.wire.close()
    }
    private fun stopCurrent() {
        ++intent // Invalidate before cancelling jobs or closing the old transport.
        lifecycle?.cancel()
        lifecycle = null
        active?.let { abort(it, BleProtocolException(BleSessionProblem.CLOSED)) }
        active = null
        while (eventQueue.tryReceive().isSuccess) { /* discard retired events */ }
    }

    fun connect(address: String) {
        checkOwner()
        check(!closed && ownerJob.isActive)
        require(address.isNotBlank())
        stopCurrent()
        val ticket = intent
        mutableState.value = BleSessionSnapshot(generation = generation)
        lifecycle = scope.launch { maintain(address, ticket) }
    }
    fun disconnect() {
        checkOwner()
        if (closed) return
        stopCurrent()
        update { it.copy(phase = BleSessionPhase.IDLE, info = null, problem = null, transportFault = null) }
    }
    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        stopCurrent()
        update { it.copy(phase = BleSessionPhase.CLOSED, info = null) }
        eventQueue.cancel()
        ownerJob.cancel()
    }

    private suspend fun maintain(address: String, ticket: Long) {
        var retries = 0
        while (currentCoroutineContext().isActive && intent == ticket) {
            var wire: BleWireConnection? = null
            var link: Link? = null
            var failure: Exception? = null
            try {
                val attemptGeneration = ++generation
                update { it.copy(phase = BleSessionPhase.CONNECTING, generation = attemptGeneration, retries = retries, info = null, problem = null, transportFault = null) }
                val opened = transport.open(address, protocol.profile) { stage ->
                    checkOwner()
                    if (intent == ticket) update { it.copy(phase = if (stage == BleLinkStage.CONNECTING) BleSessionPhase.CONNECTING else BleSessionPhase.INITIALIZING) }
                }
                wire = opened
                currentCoroutineContext().ensureActive()
                if (intent != ticket) return
                val connected = Link(attemptGeneration, opened, protocol.newSession())
                require(!connected.codec.requestIds.isEmpty())
                link = connected
                active = connected
                coroutineScope {
                    // Begin receiving before Hello; responses may precede local write completion.
                    val receiver = launch(start = CoroutineStart.UNDISPATCHED) { receive(connected) }
                    var stable: Job? = null
                    try {
                        update { it.copy(phase = BleSessionPhase.HANDSHAKING) }
                        val info = withTimeoutOrNull(handshakeTimeoutMs) {
                            connected.codec.handshake(BleCommandExchange { exchange(connected, it) })
                        } ?: throw BleProtocolException(BleSessionProblem.RESPONSE_TIMEOUT)
                        requireCurrent(connected)
                        update { it.copy(phase = BleSessionPhase.READY, info = info) }
                        stable = launch {
                            delay(stableReadyMs)
                            requireCurrent(connected)
                            retries = 0 // Brief successful handshakes do not reset the retry budget.
                            update { it.copy(retries = 0) }
                        }
                        throw connected.failure.await()
                    } finally { receiver.cancel(); stable?.cancel() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (caught: Exception) {
                failure = caught
            } finally {
                link?.let {
                    if (active === it) { abort(it, failure ?: BleProtocolException(BleSessionProblem.CLOSED)); active = null }
                }
                wire?.close()
                if (intent == ticket && !ownerJob.isActive) {
                    update { it.copy(phase = BleSessionPhase.CLOSED, info = null) }
                    eventQueue.cancel()
                }
            }
            if (intent != ticket) return
            val fault = (failure as? BleTransportException)?.fault
            val cause = when (fault) {
                BleTransportFault.LINK_LOST -> BleDisconnectCause.LINK_LOST
                BleTransportFault.CONNECT_TIMEOUT -> BleDisconnectCause.CONNECT_TIMEOUT
                BleTransportFault.OPERATION_TIMEOUT -> BleDisconnectCause.GATT_OPERATION_TIMEOUT
                else -> BleDisconnectCause.PROTOCOL_UNSUPPORTED
            }
            val delayMs = reconnectPolicy.nextDelayMs(cause, retries + 1, true,
                fault != BleTransportFault.PERMISSION, fault != BleTransportFault.BLUETOOTH_OFF)
            update { it.copy(info = null, transportFault = fault,
                problem = (failure as? BleProtocolException)?.problem
                    ?: if (failure is BleTransportException) BleSessionProblem.TRANSPORT else BleSessionProblem.PROTOCOL,
                phase = if (delayMs == null) BleSessionPhase.FAILED else BleSessionPhase.WAITING_RETRY) }
            if (delayMs == null) return
            ++retries
            update { it.copy(retries = retries) }
            delay(delayMs)
        }
    }

    suspend fun execute(command: BleDeviceCommand): BleProtocolMessage.Response {
        checkOwner()
        if (state.value.phase != BleSessionPhase.READY) throw BleProtocolException(BleSessionProblem.NOT_READY)
        return exchange(active ?: throw BleProtocolException(BleSessionProblem.CLOSED), command)
    }
    private suspend fun exchange(link: Link, command: BleDeviceCommand): BleProtocolMessage.Response {
        requireCurrent(link)
        if (!link.admissions.tryAcquire()) throw BleProtocolException(BleSessionProblem.BUSY)
        try {
            return link.mutex.withLock {
                currentCoroutineContext().ensureActive()
                requireCurrent(link)
                try {
                    // Never wrap/reuse an ID on this link. A new link gets a new codec/ID space.
                    if (link.nextId > link.codec.requestIds.last.toLong()) throw BleProtocolException(BleSessionProblem.REQUEST_IDS_EXHAUSTED)
                    val id = link.nextId.toInt()
                    link.nextId += 1
                    val request = link.codec.encode(id, command, link.wire.maxWriteBytes)
                    require(request.key.requestId == id && request.packets.isNotEmpty())
                    require(request.packets.all { it.isNotEmpty() && it.size <= link.wire.maxWriteBytes })
                    val waiting = Waiting(request.key)
                    link.waiting = waiting // Reserve expected response BEFORE the first write.
                    val response = withTimeoutOrNull(requestTimeoutMs) {
                        request.packets.forEach { link.wire.write(it) }
                        waiting.reply.await()
                    } ?: throw BleProtocolException(BleSessionProblem.RESPONSE_TIMEOUT)
                    requireCurrent(link)
                    if (!response.accepted) throw BleProtocolException(BleSessionProblem.COMMAND_REJECTED)
                    response
                } catch (cancelled: CancellationException) {
                    // Remote outcome is now unknown; retire instead of reusing sequence/queue.
                    abort(link, BleProtocolException(BleSessionProblem.CLOSED, cancelled))
                    throw cancelled
                } catch (failure: Exception) {
                    val reported = if (failure is BleProtocolException || failure is BleTransportException) failure
                        else BleProtocolException(BleSessionProblem.PROTOCOL, failure)
                    if (reported !is BleProtocolException || reported.problem != BleSessionProblem.COMMAND_REJECTED) abort(link, reported)
                    throw reported
                } finally { link.waiting = null }
            }
        } finally { link.admissions.release() }
    }

    private suspend fun receive(link: Link) {
        val assemblers = protocol.profile.subscriptions.associate { subscription ->
            val format = link.codec.frameFormat(subscription.endpoint)
            subscription.endpoint to BleFrameAssembler(format, format.maxFrameBytes)
        }
        try {
            link.wire.notifications.collect { notification ->
                requireCurrent(link)
                val assembler = assemblers[notification.endpoint] ?: throw BleProtocolException(BleSessionProblem.PROTOCOL)
                for (frame in assembler.feed(notification.bytes)) {
                    val message = link.codec.decode(notification.endpoint, frame)
                    update { it.copy(validatedFrames = it.validatedFrames + 1) }
                    when (message) {
                        is BleProtocolMessage.Response -> {
                            val waiting = link.waiting
                            if (waiting == null || waiting.key != message.key || !waiting.reply.complete(message))
                                update { it.copy(ignoredResponses = it.ignoredResponses + 1) }
                        }
                        is BleProtocolMessage.Event -> if (!eventQueue.trySend(BleSessionEvent(link.generation, message)).isSuccess)
                            throw BleProtocolException(BleSessionProblem.EVENT_OVERFLOW)
                    }
                }
            }
            abort(link, BleTransportException(BleTransportFault.LINK_LOST))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            abort(link, if (failure is BleProtocolException || failure is BleTransportException) failure
                else BleProtocolException(BleSessionProblem.PROTOCOL, failure))
        } finally { assemblers.values.forEach { it.clear() } }
    }
}
