package io.github.zcodeandroid.app.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ZCode channel-protocol client over ws://127.0.0.1:3030/ws — byte-exact port
 * of the wire behavior proven on-device by scripts/embedded/assets/
 * register-workspace-project.cjs, cross-checked against the pinned upstream
 * source (packages/rpc/src/{protocol,channelClient,channelServer,serialization}.ts
 * at 3c5b227649371507ddc4eb46ac5133afe6db1cab).
 *
 * Frame: 13-byte header [type u8][id u32 BE][ack u32 BE][len u32 BE] + payload.
 * Client -> server payload: serialize([reqType, id, channel, method]) +
 * serialize(argsArray). Server -> client: [200] initialize on open,
 * [201, id, data] success, [202, id, {message,...}] error, [204, id, data]
 * event fire, [203, id, data] raw error object.
 *
 * Enum values are read from the pinned source channels.shared.ts — NOT from
 * the earlier audit note, which had 101/203 wrong (PromiseCancel=101,
 * EventListen=102, PromiseErrorObj=203, EventFire=204).
 *
 * HONEST STATUS: the protocol constants and framing are verified against the
 * pinned source and the CI-proven JS client. A live chat round driven by this
 * Kotlin client runs in CI on the x86_64 AVD (android-verify "native-protocol"
 * stage: sendPrompt -> stream frames -> respondPermission, plus a second
 * concurrent native client); ARM64 on-device verification remains pending.
 */
class ZcodeRpcClient(
    private val wsUrl: String,
    private val scope: CoroutineScope,
) {
    enum class Conn { IDLE, CONNECTING, READY, CLOSED }

    class RpcError(
        detailMessage: String,
        val name: String? = null,
        val code: Any? = null,
        val kind: Any? = null,
        val traceId: Any? = null,
    ) : Exception(detailMessage)

    private val _conn = MutableStateFlow(Conn.IDLE)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _events = MutableSharedFlow<RpcEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RpcEvent> = _events.asSharedFlow()

    data class RpcEvent(val listenId: Int, val data: Any?)

    // Frame reassembly state (ChunkStream semantics: WS messages are stream
    // fragments, frames may straddle message boundaries).
    private val chunks = ArrayDeque<ByteArray>()
    private var chunkLen = 0

    private val nextRequestId = AtomicInteger(1)
    private val pending = HashMap<Int, PendingCall>()
    private val listeners = HashMap<Int, ListenerEntry>()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val wantsConnection = AtomicBoolean(false)
    private var initialized = false

    /**
     * Consecutive connection failures since the last READY. Drives the
     * bounded backoff; reset on initialize ([200]). The previous shape
     * restarted the counter on every scheduleReconnect, so a persistently
     * failing endpoint always reconnected at 0.5s with no backoff.
     */
    private var consecutiveFailures = 0

    /** Task-send RPC watchdog: request id + in-flight channel.method. */
    private val rpcWatchdog = java.util.concurrent.atomic.AtomicLong(0)

    private data class PendingCall(
        val channel: String,
        val method: String,
        val onComplete: (Result<Any?>) -> Unit,
        val deadlineMs: Long,
        val requestId: Int,
    )

    private class ListenerEntry(
        val channel: String,
        val event: String,
        val arg: Any?,
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS: no read timeout
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // -------------------------------------------------------------- lifecycle

    fun connect() {
        if (!wantsConnection.compareAndSet(false, true)) return
        openSocket()
    }

    private fun openSocket() {
        _conn.value = Conn.CONNECTING
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun close() {
        wantsConnection.set(false)
        reconnectJob?.cancel()
        runCatching { webSocket?.close(1000, "client closing") }
        webSocket = null
        failAllPending("client closed")
        _conn.value = Conn.CLOSED
    }

    /** Reconnect with bounded backoff while wantsConnection holds. */
    private fun scheduleReconnect(reason: String) {
        initialized = false
        failAllPending("connection lost: $reason")
        if (!wantsConnection.get()) {
            _conn.value = Conn.CLOSED
            return
        }
        _conn.value = Conn.IDLE
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (wantsConnection.get()) {
                val backoff = (500L shl consecutiveFailures.coerceAtMost(4)) // 0.5s..8s
                android.util.Log.i(
                    "ZcodeRpcClient",
                    "reconnect attempt #${consecutiveFailures + 1} after ${backoff}ms ($reason)",
                )
                delay(backoff)
                if (!wantsConnection.get()) break
                consecutiveFailures++
                openSocket()
                // openSocket is async — watch for READY; if the socket
                // fails, scheduleReconnect() (fresh reason) drives the loop
                // again and this job is superseded by the active-check above.
                // Poll briefly so a slow-but-successful connect exits here.
                var waited = 0L
                while (wantsConnection.get() && _conn.value != Conn.READY && waited < 10_000) {
                    delay(250)
                    waited += 250
                    if (_conn.value == Conn.IDLE) break
                }
                if (_conn.value == Conn.READY) return@launch
                if (_conn.value == Conn.IDLE) continue
                // CONNECTING stall past 10s: retry as a fresh backoff step.
            }
        }
    }

    // ----------------------------------------------------------------- socket

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _conn.value = Conn.CONNECTING
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            acceptChunk(bytes.toByteArray())
            drainFrames()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect("closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect(t.message ?: "failure")
        }
    }

    // ------------------------------------------------------------- wire codec

    private fun acceptChunk(bytes: ByteArray) {
        chunks.addLast(bytes)
        chunkLen += bytes.size
    }

    private fun takeBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val head = chunks.first()
            val need = n - off
            if (head.size <= need) {
                System.arraycopy(head, 0, out, off, head.size)
                off += head.size
                chunks.removeFirst()
            } else {
                System.arraycopy(head, 0, out, off, need)
                chunks[0] = head.copyOfRange(need, head.size)
                off += need
            }
        }
        chunkLen -= n
        return out
    }

    private fun drainFrames() {
        val headerSize = 13
        while (chunkLen >= headerSize) {
            // Peek header without consuming (frame may straddle chunks).
            val head = peekBytes(headerSize) ?: return
            val type = head[0].toInt() and 0xFF
            val len = ((head[9].toLong() and 0xFF) shl 24) or
                ((head[10].toLong() and 0xFF) shl 16) or
                ((head[11].toLong() and 0xFF) shl 8) or
                (head[12].toLong() and 0xFF)
            val total = headerSize + len.toInt()
            if (chunkLen < total) return
            takeBytes(headerSize)
            if (len == 0L) {
                if (type == MSG_REGULAR) onFramePayload(ByteArray(0))
                continue
            }
            val body = takeBytes(len.toInt())
            if (type == MSG_REGULAR) onFramePayload(body)
        }
    }

    private fun peekBytes(n: Int): ByteArray? {
        if (chunkLen < n) return null
        val out = ByteArray(n)
        var off = 0
        for (chunk in chunks) {
            if (off >= n) break
            val copy = minOf(chunk.size, n - off)
            System.arraycopy(chunk, 0, out, off, copy)
            off += copy
        }
        return out
    }

    private fun sendFrame(payload: ByteArray) {
        val frame = ByteArray(13 + payload.size)
        frame[0] = MSG_REGULAR.toByte()
        writeU32BE(frame, 1, 0) // id
        writeU32BE(frame, 5, 0) // ack
        writeU32BE(frame, 9, payload.size)
        System.arraycopy(payload, 0, frame, 13, payload.size)
        webSocket?.send(frame.toByteString())
    }

    private fun writeU32BE(dst: ByteArray, off: Int, v: Int) {
        dst[off] = ((v ushr 24) and 0xFF).toByte()
        dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 3] = (v and 0xFF).toByte()
    }

    // -------------------------------------------------------------- protocol

    private fun onFramePayload(payload: ByteArray) {
        val reader = Vql.Reader(payload)
        val header = try {
            reader.value()
        } catch (e: Exception) {
            return // decode error: drop frame (mirrors JS client behavior)
        }
        val headerList = header as? List<*> ?: return
        val type = (headerList.getOrNull(0) as? Number)?.toInt() ?: return
        when (type) {
            RESPONSE_INITIALIZE -> {
                initialized = true
                consecutiveFailures = 0
                _conn.value = Conn.READY
                // Events subscribed before READY are re-armed here so a
                // reconnect restores subscriptions without UI churn.
                for ((id, entry) in listeners) {
                    sendListen(id, entry.channel, entry.event, entry.arg)
                }
            }
            RESPONSE_PROMISE_SUCCESS, RESPONSE_PROMISE_ERROR, RESPONSE_PROMISE_ERROR_OBJ, RESPONSE_EVENT_FIRE -> {
                val id = (headerList.getOrNull(1) as? Number)?.toInt() ?: return
                val body = if (reader.remaining > 0) try {
                    reader.value()
                } catch (e: Exception) {
                    null
                } else null
                when (type) {
                    RESPONSE_EVENT_FIRE -> {
                        _events.tryEmit(RpcEvent(id, body))
                    }
                    else -> {
                        val pendingCall = synchronized(pending) { pending.remove(id) }
                        when (type) {
                            RESPONSE_PROMISE_SUCCESS -> pendingCall?.onComplete?.invoke(Result.success(body))
                            RESPONSE_PROMISE_ERROR -> {
                                val m = body as? Map<*, *>
                                pendingCall?.onComplete?.invoke(
                                    Result.failure(
                                        RpcError(
                                            detailMessage = m?.get("message") as? String ?: "RPC error",
                                            name = m?.get("name") as? String,
                                            code = m?.get("code"),
                                            kind = m?.get("kind"),
                                            traceId = m?.get("traceId"),
                                        )
                                    )
                                )
                            }
                            else -> pendingCall?.onComplete?.invoke(Result.failure(RpcError("RPC error object")))
                        }
                    }
                }
            }
            REQUEST_PROMISE, REQUEST_EVENT_LISTEN -> {
                // Server->client request (rare): answer with an empty result so
                // the server never waits on us (mirrors the proven JS client).
                val id = (headerList.getOrNull(1) as? Number)?.toInt() ?: return
                val payloadOut = ByteArrayOutputStream()
                Vql.serializeInto(payloadOut, listOf(RESPONSE_PROMISE_SUCCESS, id))
                Vql.serializeInto(payloadOut, emptyMap<String, Any?>())
                sendFrame(payloadOut.toByteArray())
            }
            else -> {
                // Unknown type: ignore (forward compatibility).
            }
        }
    }

    // ----------------------------------------------------------------- calls

    /**
     * Promise RPC. ProxyChannel convention (upstream packages/rpc/src/
     * proxy-channel.ts): the wire body is the ARRAY of the method's positional
     * arguments — a bare object would be spread as zero arguments.
     *
     * TIMEOUT vs VOID-RESPONSE (run 42086199982 root cause; also the 0.5.0
     * owner-reported "sendPrompt 20s RPC timeout" mechanism): upstream
     * sendPrompt is Promise<void> — its ack frame (sent at committed
     * TurnStarted, zcodeAgentService.ts sendConversationCommandV4) carries an
     * UNDEFINED body, which VQL decodes to Kotlin null. A bare
     * withTimeoutOrNull cannot distinguish "resolved with null" from "timed
     * out", so every void method was misreported as a 20s timeout the moment
     * its ack arrived (probe test failed 8.4s in). Completions are wrapped in
     * [CallCompleted] so only a real elapsed timeout yields null. NO timeout
     * value was changed — the default stays 20s; the defect was the
     * null-completion conflation, proven by the 8.432s JUnit timeline.
     */
    private class CallCompleted(val value: Any?)

    suspend fun call(channel: String, method: String, arg: Any? = null, timeoutMs: Long = 20_000): Any? {
        awaitReady(timeoutMs)
        val id = nextRequestId.getAndIncrement()
        val payloadOut = ByteArrayOutputStream()
        Vql.serializeInto(payloadOut, listOf(REQUEST_PROMISE, id, channel, method))
        Vql.serializeInto(payloadOut, if (arg == null) emptyList<Any?>() else listOf(arg))
        val wire = payloadOut.toByteArray()

        rpcWatchdog.set(id)
        android.util.Log.i(
            "ZcodeRpcClient",
            "rpc requestId=$id $channel.$method timeoutMs=$timeoutMs",
        )
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                synchronized(pending) { pending[id] = PendingCall(channel, method, { r ->
                    if (cont.isActive) r.fold(
                        onSuccess = { cont.resume(CallCompleted(it)) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }, System.currentTimeMillis() + timeoutMs, id) }
                sendFrame(wire)
            }
        }
        if (result == null) {
            // Timed out: drop the pending entry so a late response is ignored.
            android.util.Log.w(
                "ZcodeRpcClient",
                "rpc TIMEOUT requestId=$id $channel.$method after ${timeoutMs}ms",
            )
            synchronized(pending) { pending.remove(id) }
            throw RpcError("RPC timeout: $channel.$method requestId=$id (${timeoutMs}ms)")
        }
        return (result as CallCompleted).value
    }

    /**
     * Event subscription handle: the SharedFlow of event data plus a
     * deterministic teardown that cancels collection and sends EventDispose.
     */
    class Listener internal constructor(
        val flow: SharedFlow<Any?>,
        private val onDispose: () -> Unit,
    ) {
        fun dispose() = onDispose()
    }

    /**
     * Event subscription (EventListen). Ids share the request-id space;
     * subscriptions made before READY are re-armed on initialize.
     */
    fun listen(channel: String, event: String, arg: Any? = null): Listener {
        val id = nextRequestId.getAndIncrement()
        val entry = ListenerEntry(channel, event, arg)
        listeners[id] = entry
        if (initialized && _conn.value == Conn.READY) {
            sendListen(id, channel, event, arg)
        }
        val flow = MutableSharedFlow<Any?>(extraBufferCapacity = 256)
        val job = scope.launch {
            events.collect { e ->
                if (e.listenId == id) flow.tryEmit(e.data)
            }
        }
        return Listener(flow) {
            job.cancel()
            listeners.remove(id)
            if (initialized && _conn.value == Conn.READY) sendDispose(id)
        }
    }

    private fun sendListen(id: Int, channel: String, event: String, arg: Any?) {
        val payloadOut = ByteArrayOutputStream()
        Vql.serializeInto(payloadOut, listOf(REQUEST_EVENT_LISTEN, id, channel, event))
        // EventListen body = the SINGLE subscription argument serialized AS-IS
        // (pinned 3c5b227: channelClient.ts sendRequest -> serialize(writer, arg);
        // channelServer.ts onEventListen passes request.arg straight to
        // channel.listen; proxy-channel.ts dynamic events invoke
        // onDynamicXxx(arg) with that one value). The Promise convention of
        // wrapping positional args in an array does NOT apply here: wrapping
        // made the adapter's onDynamicTaskEvent(params) read params.taskId off
        // an Array -> undefined, keying the subscription to nothing. The server
        // neither fires events nor returns an error — silent zero-event
        // delivery (run 42092285488: sendPrompt acked, kinds=[] over 300s).
        // null serializes to TAG_UNDEFINED, exactly the official
        // serialize(writer, undefined) for arg-less static events.
        Vql.serializeInto(payloadOut, arg)
        sendFrame(payloadOut.toByteArray())
    }

    private fun sendDispose(id: Int) {
        val payloadOut = ByteArrayOutputStream()
        Vql.serializeInto(payloadOut, listOf(REQUEST_EVENT_DISPOSE, id))
        Vql.serializeInto(payloadOut, RpcUndefined)
        sendFrame(payloadOut.toByteArray())
    }

    private suspend fun awaitReady(timeoutMs: Long) {
        if (_conn.value == Conn.READY && initialized) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_conn.value == Conn.READY && initialized) return
            delay(50)
        }
        throw RpcError("RPC not ready within ${timeoutMs}ms (conn=${_conn.value})")
    }

    private fun failAllPending(reason: String) {
        val snapshot = synchronized(pending) {
            val copy = pending.toMap()
            pending.clear()
            copy
        }
        for ((_, call) in snapshot) {
            call.onComplete(Result.failure(RpcError(reason)))
        }
    }

    suspend fun healthProbe(timeoutMs: Long = 10_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            runCatching { call("project", "readProjects", null, timeoutMs) }.isSuccess
        } ?: false

    companion object {
        // Frame message types (packages/rpc/src/protocol.ts ProtocolMessageType)
        const val MSG_REGULAR = 1
        // RequestType (channels.shared.ts) — pinned source values
        const val REQUEST_PROMISE = 100
        const val REQUEST_PROMISE_CANCEL = 101
        const val REQUEST_EVENT_LISTEN = 102
        const val REQUEST_EVENT_DISPOSE = 103
        // ResponseType (channels.shared.ts) — pinned source values
        const val RESPONSE_INITIALIZE = 200
        const val RESPONSE_PROMISE_SUCCESS = 201
        const val RESPONSE_PROMISE_ERROR = 202
        const val RESPONSE_PROMISE_ERROR_OBJ = 203
        const val RESPONSE_EVENT_FIRE = 204
    }
}
