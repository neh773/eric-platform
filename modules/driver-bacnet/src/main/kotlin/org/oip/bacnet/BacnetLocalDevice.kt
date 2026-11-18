package org.oip.bacnet

import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The gateway's BACnet/IP device (DEV-012). It owns the one UDP socket every remote device on it shares.
 *
 * Threads: a receive thread (`bacnet-rx-<name>`) that only decodes datagrams and completes the matching
 * transaction, and a timer thread (`bacnet-timer-<name>`) for APDU timeouts, retries and rebinding. Callers
 * get a [CompletableFuture]; nothing here blocks the caller's thread on the network.
 *
 * Confirmed requests: an invoke id per peer, at most [LocalDeviceConfig.maxOutstanding] in flight, at
 * most [LocalDeviceConfig.maxQueued] waiting, each sent up to `1 + apduRetries` times with
 * [LocalDeviceConfig.apduTimeoutMs] between tries. A reply that arrives after its transaction ended is
 * dropped, because the invoke id is freed only when the transaction ends.
 *
 * The device answers Who-Is for its own instance with I-Am, and ReadProperty or ReadPropertyMultiple of
 * its device object, so other BACnet devices can see the identity it is configured with. It does not
 * accept segmented messages. doc source: FEATURES DEV-012
 */
class BacnetLocalDevice(val config: LocalDeviceConfig) : AutoCloseable {
    private val log = Logger.getLogger("org.oip.bacnet.${config.name}")
    private val lock = Any()
    private val inFlight = HashMap<TxKey, Tx>()
    private val waiting = ArrayDeque<Tx>()
    private val nextInvoke = HashMap<InetSocketAddress, Int>()
    private val iAmListeners = CopyOnWriteArrayList<(IAm, BacnetAddress) -> Unit>()
    private val self = ObjectId(BacnetObjectType.DEVICE, config.deviceInstance)

    @Volatile private var channel: DatagramChannel? = null
    @Volatile private var receiver: Thread? = null
    @Volatile private var timer: ScheduledExecutorService? = null
    @Volatile private var rebind: ScheduledFuture<*>? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null
        private set
    @Volatile var since: Instant = Instant.now()
        private set

    val requestCount = AtomicLong()
    val retryCount = AtomicLong()
    val timeoutCount = AtomicLong()
    val malformedCount = AtomicLong()

    /** True while the socket is bound. */
    val isBound: Boolean get() = channel?.isOpen == true

    /** UDP port bound now; useful when [LocalDeviceConfig.port] is 0. */
    val boundPort: Int get() = (channel?.localAddress as? InetSocketAddress)?.port ?: -1

    /** Confirmed requests in flight plus waiting. */
    val pendingCount: Int get() = synchronized(lock) { inFlight.size + waiting.size }

    val broadcastTarget: InetSocketAddress get() = InetSocketAddress(ipv4(config.broadcastAddress), config.port)

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bacnet-timer-${config.name}").apply { isDaemon = true } }
        }
        if (!bind()) {
            rebind = timer?.scheduleWithFixedDelay({ if (running && channel == null) bind() }, 2_000, 2_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun bind(): Boolean {
        val ch = DatagramChannel.open()
        try {
            ch.setOption(StandardSocketOptions.SO_BROADCAST, true)
            ch.bind(InetSocketAddress(ipv4(config.bindAddress), config.port))
            ch.configureBlocking(true)
        } catch (e: IOException) {
            runCatching { ch.close() }
            lastError = "cannot bind UDP ${config.bindAddress}:${config.port}: ${e.message}"
            log.log(Level.WARNING, lastError)
            return false
        }
        synchronized(lock) {
            if (!running) { runCatching { ch.close() }; return false }
            channel = ch
            lastError = null
            since = Instant.now()
            rebind?.cancel(false)
            receiver = Thread({ receive(ch) }, "bacnet-rx-${config.name}").apply { isDaemon = true; start() }
        }
        return true
    }

    fun stop() {
        val pending: List<Tx>
        synchronized(lock) {
            if (!running) return
            running = false
            pending = inFlight.values.toList() + waiting.toList()
            inFlight.clear()
            waiting.clear()
        }
        runCatching { channel?.close() }
        receiver?.join(5_000)
        receiver = null
        channel = null
        timer?.let { it.shutdownNow(); it.awaitTermination(5, TimeUnit.SECONDS) }
        timer = null
        for (tx in pending) { tx.timeout?.cancel(false); tx.future.completeExceptionally(NotConnectedException("local device ${config.name} stopped")) }
        since = Instant.now()
    }

    override fun close() = stop()

    fun addIAmListener(listener: (IAm, BacnetAddress) -> Unit): AutoCloseable {
        iAmListeners += listener
        return AutoCloseable { iAmListeners -= listener }
    }

    /** Who-Is for [low]..[high] to [target], or to the broadcast address when [target] is null. */
    fun whoIs(low: Int?, high: Int?, target: InetSocketAddress? = null) {
        val apdu = BacnetFrames.unconfirmed(BacnetService.WHO_IS, BacnetFrames.whoIs(low, high))
        send(BacnetFrames.frame(apdu, null, broadcast = target == null, expectingReply = false), target ?: broadcastTarget)
    }

    /** Send an unconfirmed request to one device. False when the socket is closed or the send failed. */
    fun sendUnconfirmed(dest: BacnetAddress, service: Int, data: ByteArray): Boolean {
        if (!running || channel == null) return false
        return send(BacnetFrames.frame(BacnetFrames.unconfirmed(service, data), dest, broadcast = false, expectingReply = false), dest.socket)
    }

    /** Send a confirmed request. The future fails with [TimeoutException] after the last retry. */
    fun request(dest: BacnetAddress, service: Int, data: ByteArray, maxApdu: Int = config.maxApduLength): CompletableFuture<Apdu> {
        val tx = Tx(dest, service, data, maxApdu)
        if (!running || channel == null) {
            tx.future.completeExceptionally(NotConnectedException(lastError ?: "local device ${config.name} is not running"))
            return tx.future
        }
        val toSend: Tx?
        synchronized(lock) {
            toSend = if (inFlight.size < config.maxOutstanding && assign(tx)) tx else {
                if (waiting.size >= config.maxQueued) {
                    tx.future.completeExceptionally(IllegalStateException("request queue of local device ${config.name} is full"))
                    return tx.future
                }
                waiting.addLast(tx)
                null
            }
        }
        if (toSend != null) transmit(toSend)
        return tx.future
    }

    /** Under [lock]: pick a free invoke id for this peer. */
    private fun assign(tx: Tx): Boolean {
        val peer = tx.dest.socket
        val start = nextInvoke[peer] ?: 0
        for (i in 0 until 256) {
            val id = (start + i) and 0xFF
            val key = TxKey(peer, id)
            if (key !in inFlight) {
                tx.invokeId = id
                inFlight[key] = tx
                nextInvoke[peer] = (id + 1) and 0xFF
                return true
            }
        }
        return false
    }

    private fun transmit(tx: Tx) {
        val apdu = BacnetFrames.confirmed(tx.invokeId, tx.service, tx.data, config.maxApduLength)
        val frame = BacnetFrames.frame(apdu, tx.dest, broadcast = false, expectingReply = true)
        tx.attempts++
        requestCount.incrementAndGet()
        val t = timer
        if (t == null) { finish(tx, null, NotConnectedException("local device ${config.name} stopped")); return }
        try {
            tx.timeout = t.schedule({ onTimeout(tx, tx.attempts) }, config.apduTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            finish(tx, null, NotConnectedException("local device ${config.name} stopped"))
            return
        }
        if (!send(frame, tx.dest.socket)) finish(tx, null, NotConnectedException(lastError ?: "send failed"))
    }

    private fun onTimeout(tx: Tx, attempt: Int) {
        synchronized(lock) {
            if (inFlight[TxKey(tx.dest.socket, tx.invokeId)] !== tx || tx.attempts != attempt) return
        }
        if (tx.attempts <= config.apduRetries) {
            retryCount.incrementAndGet()
            transmit(tx)
            return
        }
        timeoutCount.incrementAndGet()
        finish(tx, null, TimeoutException("no reply from ${tx.dest} after ${tx.attempts} tries of ${config.apduTimeoutMs} ms"))
    }

    private fun finish(tx: Tx, reply: Apdu?, error: Throwable?) {
        val next = ArrayList<Tx>()
        synchronized(lock) {
            val key = TxKey(tx.dest.socket, tx.invokeId)
            if (inFlight[key] === tx) inFlight.remove(key)
            else if (!waiting.remove(tx)) return
            while (waiting.isNotEmpty() && inFlight.size < config.maxOutstanding) {
                val w = waiting.first()
                if (!assign(w)) break
                waiting.removeFirst()
                next += w
            }
        }
        tx.timeout?.cancel(false)
        if (error != null) tx.future.completeExceptionally(error) else tx.future.complete(reply)
        for (w in next) transmit(w)
    }

    private fun send(frame: ByteArray, to: InetSocketAddress): Boolean {
        val ch = channel ?: return false
        return try {
            ch.send(ByteBuffer.wrap(frame), to)
            true
        } catch (e: IOException) {
            lastError = "send to $to failed: ${e.message}"
            log.log(Level.FINE, lastError, e)
            false
        }
    }

    // ---- receive thread -----------------------------------------------------------------------

    private fun receive(ch: DatagramChannel) {
        val buffer = ByteBuffer.allocate(2048)
        while (running) {
            buffer.clear()
            val from = try {
                ch.receive(buffer) as? InetSocketAddress ?: continue
            } catch (_: AsynchronousCloseException) {
                break
            } catch (_: ClosedChannelException) {
                break
            } catch (e: IOException) {
                log.log(Level.FINE, "receive failed", e)
                continue
            }
            val n = buffer.position()
            val received = try {
                BacnetFrames.parse(buffer.array(), n, from) ?: continue
            } catch (e: RuntimeException) {
                malformedCount.incrementAndGet()
                log.log(Level.FINE, "malformed datagram from $from", e)
                continue
            }
            try {
                handle(received)
            } catch (e: RuntimeException) {
                malformedCount.incrementAndGet()
                log.log(Level.FINE, "could not handle a datagram from $from", e)
            }
        }
    }

    private fun handle(r: Received) {
        when (val apdu = r.apdu) {
            is Apdu.Unconfirmed -> when (apdu.service) {
                BacnetService.I_AM -> {
                    val iAm = IAm.parse(apdu.data)
                    for (l in iAmListeners) runCatching { l(iAm, r.source) }
                }
                BacnetService.WHO_IS -> {
                    if (WhoIs.parse(apdu.data).matches(config.deviceInstance)) {
                        val reply = BacnetFrames.unconfirmed(
                            BacnetService.I_AM,
                            BacnetFrames.iAm(config.deviceInstance, config.maxApduLength, SEGMENTATION_NONE, config.vendorId),
                        )
                        send(BacnetFrames.frame(reply, r.source, broadcast = false, expectingReply = false), r.source.socket)
                    }
                }
            }
            is Apdu.ConfirmedRequest -> answer(r.source, apdu)
            is Apdu.SimpleAck -> reply(r.source, apdu.invokeId, apdu)
            is Apdu.ComplexAck -> {
                if (apdu.segmented) {
                    // We never accept segmentation; tell the peer and end the transaction.
                    send(BacnetFrames.frame(BacnetFrames.abort(apdu.invokeId, AbortReason.SEGMENTATION_NOT_SUPPORTED, false), r.source, false, false), r.source.socket)
                    reply(r.source, apdu.invokeId, Apdu.Abort(apdu.invokeId, AbortReason.SEGMENTATION_NOT_SUPPORTED, fromServer = false))
                } else {
                    reply(r.source, apdu.invokeId, apdu)
                }
            }
            is Apdu.Error -> reply(r.source, apdu.invokeId, apdu)
            is Apdu.Reject -> reply(r.source, apdu.invokeId, apdu)
            is Apdu.Abort -> reply(r.source, apdu.invokeId, apdu)
            is Apdu.Other -> Unit
        }
    }

    private fun reply(source: BacnetAddress, invokeId: Int, apdu: Apdu) {
        val tx = synchronized(lock) { inFlight[TxKey(source.socket, invokeId)] } ?: return
        val service = when (apdu) {
            is Apdu.SimpleAck -> apdu.service
            is Apdu.ComplexAck -> apdu.service
            is Apdu.Error -> apdu.service
            else -> tx.service
        }
        if (service != tx.service) return
        finish(tx, apdu, null)
    }

    /** Answer requests other devices send to this one: its device object only. */
    private fun answer(source: BacnetAddress, req: Apdu.ConfirmedRequest) {
        val response = try {
            if (req.segmented) {
                BacnetFrames.abort(req.invokeId, AbortReason.SEGMENTATION_NOT_SUPPORTED, true)
            } else when (req.service) {
                BacnetService.READ_PROPERTY -> {
                    val r = TagReader(req.data)
                    val obj = r.ctxObjectId(0)
                    val prop = r.ctxUnsigned(1).toInt()
                    val index = r.optCtxUnsigned(2)?.toInt()
                    if (!isSelf(obj)) BacnetFrames.error(req.invokeId, req.service, BacnetError.CLASS_OBJECT, BacnetError.CODE_UNKNOWN_OBJECT)
                    else {
                        val value = ownProperty(prop)
                        if (value == null) BacnetFrames.error(req.invokeId, req.service, BacnetError.CLASS_PROPERTY, BacnetError.CODE_UNKNOWN_PROPERTY)
                        else if (index != null && prop != BacnetProperty.OBJECT_LIST) BacnetFrames.error(req.invokeId, req.service, BacnetError.CLASS_PROPERTY, BacnetError.CODE_PROPERTY_IS_NOT_AN_ARRAY)
                        else if (index != null && index > 1) BacnetFrames.error(req.invokeId, req.service, BacnetError.CLASS_PROPERTY, BacnetError.CODE_INVALID_ARRAY_INDEX)
                        else {
                            // The object list holds only the device object: element 0 is its length (1), element 1 the device.
                            val element: (TagWriter) -> Unit = when (index) { null -> value; 0 -> { w -> w.appUnsigned(1) }; else -> { w -> w.appObjectId(self) } }
                            val w = TagWriter()
                            w.ctxObjectId(0, self); w.ctxEnumerated(1, prop.toLong()); if (index != null) w.ctxUnsigned(2, index.toLong())
                            w.open(3); element(w); w.close(3)
                            BacnetFrames.complexAck(req.invokeId, req.service, w.bytes())
                        }
                    }
                }
                BacnetService.READ_PROPERTY_MULTIPLE -> {
                    val r = TagReader(req.data)
                    val w = TagWriter()
                    while (!r.atEnd()) {
                        val obj = r.ctxObjectId(0)
                        r.expectOpen(1)
                        w.ctxObjectId(0, if (isSelf(obj)) self else obj)
                        w.open(1)
                        while (!r.isClose(1)) {
                            val prop = r.ctxUnsigned(0).toInt()
                            r.optCtxUnsigned(1)
                            w.ctxEnumerated(2, prop.toLong())
                            val value = if (isSelf(obj)) ownProperty(prop) else null
                            if (value != null) { w.open(4); value(w); w.close(4) }
                            else {
                                w.open(5)
                                w.appEnumerated(if (isSelf(obj)) BacnetError.CLASS_PROPERTY.toLong() else BacnetError.CLASS_OBJECT.toLong())
                                w.appEnumerated(if (isSelf(obj)) BacnetError.CODE_UNKNOWN_PROPERTY.toLong() else BacnetError.CODE_UNKNOWN_OBJECT.toLong())
                                w.close(5)
                            }
                        }
                        r.expectClose(1)
                        w.close(1)
                    }
                    if (w.size + 3 > minOf(req.maxApdu, config.maxApduLength)) BacnetFrames.abort(req.invokeId, AbortReason.SEGMENTATION_NOT_SUPPORTED, true)
                    else BacnetFrames.complexAck(req.invokeId, req.service, w.bytes())
                }
                else -> BacnetFrames.reject(req.invokeId, RejectReason.UNRECOGNIZED_SERVICE)
            }
        } catch (e: BacnetDecodeException) {
            BacnetFrames.reject(req.invokeId, RejectReason.OTHER)
        }
        send(BacnetFrames.frame(response, source, broadcast = false, expectingReply = false), source.socket)
    }

    private fun isSelf(obj: ObjectId) = obj.type == BacnetObjectType.DEVICE && (obj.instance == config.deviceInstance || obj.instance == ObjectId.MAX_INSTANCE)

    private fun ownProperty(prop: Int): ((TagWriter) -> Unit)? = when (prop) {
        BacnetProperty.OBJECT_IDENTIFIER -> { w -> w.appObjectId(self) }
        BacnetProperty.OBJECT_NAME -> { w -> w.appString(config.deviceName) }
        BacnetProperty.OBJECT_TYPE -> { w -> w.appEnumerated(BacnetObjectType.DEVICE.toLong()) }
        BacnetProperty.SYSTEM_STATUS -> { w -> w.appEnumerated(0) }
        BacnetProperty.VENDOR_NAME -> { w -> w.appString(config.vendorName) }
        BacnetProperty.VENDOR_IDENTIFIER -> { w -> w.appUnsigned(config.vendorId.toLong()) }
        BacnetProperty.MODEL_NAME -> { w -> w.appString(config.modelName) }
        BacnetProperty.DESCRIPTION -> { w -> w.appString(config.description) }
        BacnetProperty.PROTOCOL_VERSION -> { w -> w.appUnsigned(1) }
        BacnetProperty.PROTOCOL_REVISION -> { w -> w.appUnsigned(14) }
        BacnetProperty.MAX_APDU_LENGTH_ACCEPTED -> { w -> w.appUnsigned(config.maxApduLength.toLong()) }
        BacnetProperty.SEGMENTATION_SUPPORTED -> { w -> w.appEnumerated(SEGMENTATION_NONE.toLong()) }
        BacnetProperty.APDU_TIMEOUT -> { w -> w.appUnsigned(config.apduTimeoutMs) }
        BacnetProperty.NUMBER_OF_APDU_RETRIES -> { w -> w.appUnsigned(config.apduRetries.toLong()) }
        BacnetProperty.OBJECT_LIST -> { w -> w.appObjectId(self) }
        else -> null
    }

    private data class TxKey(val peer: InetSocketAddress, val invokeId: Int)

    private class Tx(val dest: BacnetAddress, val service: Int, val data: ByteArray, val maxApdu: Int) {
        val future = CompletableFuture<Apdu>()
        @Volatile var invokeId = -1
        @Volatile var attempts = 0
        @Volatile var timeout: ScheduledFuture<*>? = null
    }

    companion object {
        /** BACnetSegmentation `no-segmentation`. */
        const val SEGMENTATION_NONE = 3
    }
}
