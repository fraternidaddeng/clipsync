package com.clipsync.android.spike

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.clipsync.android.sync.Bt1AuthProof
import com.clipsync.android.sync.Bt1FrameDecryptor
import com.clipsync.android.sync.Bt1FrameEncryptor
import com.clipsync.android.sync.Bt1Frames
import com.clipsync.android.sync.Bt1HandshakeCodec
import com.clipsync.android.sync.Bt1HandshakeMessage
import com.clipsync.android.sync.Bt1KeySchedule
import com.clipsync.android.sync.Bt1Role
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom

/** Spike parameters chosen in the UI; device identities and epoch stay fixed defaults. */
class Bt1SpikeConfig(
    val useBt1: Boolean,
    val secret: ByteArray,
    val transferBytes: Int,
)

/**
 * PHASE 0 SPIKE ONLY (docs/bluetooth-phase0-spike.md): RFCOMM client against the Windows
 * spike listener. Connects to the frozen ClipSync service UUID on a bonded PC, runs the
 * real phase 1 bt1 handshake (the exact ClipSync.Core-mirroring types from the main
 * source set), then drives the spike measurement protocol:
 * "ping <bytes>" (RTT), "up <n>" + "data <bytes>" frames (uplink), "down <n>" (downlink),
 * "bye" (graceful end). All calls must run on an IO dispatcher. Every measurement is
 * emitted through [log] as a `SPIKE_RESULT:key=value` line.
 *
 * MissingPermission is suppressed because the spike activity gates every entry point on
 * BLUETOOTH_CONNECT (runtime on API 31+, install-time below).
 */
@SuppressLint("MissingPermission")
class Bt1SpikeRunner(
    private val config: Bt1SpikeConfig,
    private val log: (String) -> Unit,
) {
    private var socket: BluetoothSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var encryptor: Bt1FrameEncryptor? = null
    private var decryptor: Bt1FrameDecryptor? = null

    val isOpen: Boolean get() = socket != null

    /** Dials the RFCOMM socket and, in bt1 mode, completes the client handshake. */
    fun connectAndHandshake(device: BluetoothDevice) {
        check(socket == null) { "已有打开的连接，请先断开。" }
        log("SPIKE_RESULT:mode=${if (config.useBt1) "bt1" else "raw"}")
        log("SPIKE_RESULT:secret_fingerprint=${Bt1SpikeDefaults.secretFingerprint(config.secret)}")
        log("SPIKE_RESULT:target_name=${device.name ?: "unknown"}")
        log("SPIKE_RESULT:target_address=${device.address}")
        log("SPIKE_RESULT:target_bond_state=${device.bondState}")

        // createRfcommSocketToServiceRecord = secure (authenticated + encrypted) socket
        // resolved through an SDP lookup of the frozen service UUID — the exact call
        // phase 3 will ship.
        val candidate = device.createRfcommSocketToServiceRecord(Bt1SpikeDefaults.SERVICE_UUID)
        val connectStarted = SystemClock.elapsedRealtime()
        try {
            candidate.connect()
        } catch (exception: IOException) {
            log("SPIKE_RESULT:connect=failed")
            log("SPIKE_RESULT:connect_error=${exception.javaClass.simpleName}: ${exception.message}")
            candidate.closeQuietly()
            throw exception
        }
        val connectMs = SystemClock.elapsedRealtime() - connectStarted
        socket = candidate
        input = DataInputStream(candidate.inputStream)
        output = DataOutputStream(candidate.outputStream)
        log("SPIKE_RESULT:connect=ok")
        log("SPIKE_RESULT:connect_ms=$connectMs")

        if (!config.useBt1) {
            log("SPIKE_RESULT:bt1_handshake=skipped_raw_mode")
            return
        }

        val handshakeStarted = SystemClock.elapsedRealtime()
        try {
            runClientHandshake()
        } catch (exception: Exception) {
            log("SPIKE_RESULT:bt1_handshake=failed")
            log("SPIKE_RESULT:bt1_handshake_error=${exception.javaClass.simpleName}: ${exception.message}")
            closeQuietly()
            throw exception
        }
        log("SPIKE_RESULT:bt1_handshake=ok")
        log("SPIKE_RESULT:bt1_handshake_ms=${SystemClock.elapsedRealtime() - handshakeStarted}")
    }

    /** Client half of the docs/protocol-bt1.md §3 handshake, using the real phase 1 types. */
    private fun runClientHandshake() {
        val nonceClient = ByteArray(Bt1AuthProof.NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        sendHandshake(
            Bt1HandshakeCodec.serializeHello(
                Bt1Role.CLIENT,
                Bt1SpikeDefaults.CLIENT_DEVICE_ID,
                Bt1SpikeDefaults.TRUST_EPOCH,
                nonceClient,
            ),
        )

        val listenerHello = readHandshake()
        check(listenerHello is Bt1HandshakeMessage.Hello && listenerHello.senderRole == Bt1Role.LISTENER) {
            "第二条握手消息不是 bt1_listener_hello"
        }
        check(listenerHello.deviceId == Bt1SpikeDefaults.LISTENER_DEVICE_ID) {
            "listener device_id 与 spike 配置不符"
        }
        check(listenerHello.trustEpoch == Bt1SpikeDefaults.TRUST_EPOCH) {
            "listener trust_epoch 与 spike 配置不符"
        }
        val nonceListener = listenerHello.nonce

        val clientProof =
            Bt1AuthProof.compute(
                config.secret,
                Bt1Role.CLIENT,
                nonceClient,
                nonceListener,
                Bt1SpikeDefaults.CLIENT_DEVICE_ID,
                Bt1SpikeDefaults.LISTENER_DEVICE_ID,
                Bt1SpikeDefaults.TRUST_EPOCH,
            )
        sendHandshake(Bt1HandshakeCodec.serializeAuth(Bt1Role.CLIENT, clientProof))

        val listenerAuth = readHandshake()
        check(listenerAuth is Bt1HandshakeMessage.Auth && listenerAuth.senderRole == Bt1Role.LISTENER) {
            "第四条握手消息不是 bt1_listener_auth（可能是密钥不匹配触发的 bt1_error）"
        }
        val listenerProofValid =
            Bt1AuthProof.verify(
                config.secret,
                Bt1Role.LISTENER,
                nonceClient,
                nonceListener,
                Bt1SpikeDefaults.CLIENT_DEVICE_ID,
                Bt1SpikeDefaults.LISTENER_DEVICE_ID,
                Bt1SpikeDefaults.TRUST_EPOCH,
                listenerAuth.proof,
            )
        check(listenerProofValid) { "listener 证明校验失败（两端 secret/设备 ID/epoch 不一致）" }

        val keys = Bt1KeySchedule.derive(config.secret, nonceClient, nonceListener)
        encryptor = Bt1FrameEncryptor(keys.clientToListener)
        decryptor = Bt1FrameDecryptor(keys.listenerToClient)
    }

    /** Round-trip test: small payload echoed by the listener, [iterations] times. */
    fun runRtt(
        iterations: Int = RTT_ITERATIONS,
        payloadBytes: Int = RTT_PAYLOAD_BYTES,
    ) {
        checkOpen()
        val payload = ByteArray(payloadBytes)
        PING_PREFIX.copyInto(payload)
        for (index in PING_PREFIX.size until payloadBytes) {
            payload[index] = 'x'.code.toByte()
        }

        val samplesMs = DoubleArray(iterations)
        for (index in 0 until iterations) {
            val started = System.nanoTime()
            sendPayload(payload)
            val echoed = receivePayload()
            val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
            check(echoed.contentEquals(payload)) { "echo 载荷与发送不一致" }
            samplesMs[index] = elapsed
        }

        val sorted = samplesMs.sorted()
        log("SPIKE_RESULT:rtt_count=$iterations")
        log("SPIKE_RESULT:rtt_payload_bytes=$payloadBytes")
        log("SPIKE_RESULT:rtt_ms_min=${"%.1f".format(sorted.first())}")
        log("SPIKE_RESULT:rtt_ms_median=${"%.1f".format(sorted[iterations / 2])}")
        log("SPIKE_RESULT:rtt_ms_avg=${"%.1f".format(samplesMs.average())}")
        log("SPIKE_RESULT:rtt_ms_max=${"%.1f".format(sorted.last())}")
    }

    /** Uplink throughput: phone -> PC, [config.transferBytes] data bytes in 32 KiB frames. */
    fun runUplink() {
        checkOpen()
        val total = config.transferBytes
        val chunk = ByteArray(DATA_PREFIX.size + DATA_CHUNK_BYTES)
        DATA_PREFIX.copyInto(chunk)
        SecureRandom().nextBytes(chunk)
        DATA_PREFIX.copyInto(chunk) // keep the prefix after randomizing the buffer

        sendPayload("up $total".toByteArray(Charsets.US_ASCII))
        val started = SystemClock.elapsedRealtime()
        var sent = 0
        while (sent < total) {
            val dataLength = minOf(DATA_CHUNK_BYTES, total - sent)
            sendPayload(chunk.copyOfRange(0, DATA_PREFIX.size + dataLength))
            sent += dataLength
        }
        val ack = String(receivePayload(), Charsets.US_ASCII)
        val elapsedMs = SystemClock.elapsedRealtime() - started
        check(ack == "up-ok $sent") { "上行确认异常: $ack" }
        log("SPIKE_RESULT:up_bytes=$sent")
        log("SPIKE_RESULT:up_ms=$elapsedMs")
        if (elapsedMs > 0) {
            log("SPIKE_RESULT:up_kib_per_s=${"%.1f".format(sent / 1024.0 / (elapsedMs / 1000.0))}")
        }
    }

    /** Downlink throughput: PC -> phone, [config.transferBytes] data bytes. */
    fun runDownlink() {
        checkOpen()
        val total = config.transferBytes
        sendPayload("down $total".toByteArray(Charsets.US_ASCII))
        val started = SystemClock.elapsedRealtime()
        var received = 0
        while (received < total) {
            val payload = receivePayload()
            check(payload.size > DATA_PREFIX.size && payload.copyOfRange(0, DATA_PREFIX.size).contentEquals(DATA_PREFIX)) {
                "下行测试期间收到非 data 帧"
            }
            received += payload.size - DATA_PREFIX.size
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        log("SPIKE_RESULT:down_bytes=$received")
        log("SPIKE_RESULT:down_ms=$elapsedMs")
        if (elapsedMs > 0) {
            log("SPIKE_RESULT:down_kib_per_s=${"%.1f".format(received / 1024.0 / (elapsedMs / 1000.0))}")
        }
    }

    /** Sends "bye", waits for the listener's "bye", then closes the socket. */
    fun closeGracefully() {
        checkOpen()
        sendPayload("bye".toByteArray(Charsets.US_ASCII))
        val reply = String(receivePayload(), Charsets.US_ASCII)
        log("SPIKE_RESULT:bye_ack=${if (reply == "bye") "ok" else reply}")
        closeQuietly()
        log("SPIKE_RESULT:session=completed")
    }

    fun closeQuietly() {
        socket?.closeQuietly()
        socket = null
        input = null
        output = null
        encryptor = null
        decryptor = null
    }

    private fun checkOpen() {
        checkNotNull(socket) { "尚未连接" }
    }

    // ---- frame IO -----------------------------------------------------------------

    private fun sendHandshake(json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val stream = checkNotNull(output)
        stream.writeInt(payload.size) // DataOutputStream writes big-endian: UINT32_BE prefix
        stream.write(payload)
        stream.flush()
    }

    private fun readHandshake(): Bt1HandshakeMessage {
        val payload = readFrame(Bt1Frames.MAX_HANDSHAKE_PAYLOAD_LENGTH)
        val message = Bt1HandshakeCodec.parse(String(payload, Charsets.UTF_8))
        if (message is Bt1HandshakeMessage.ChannelError) {
            throw IOException("对端在握手期间返回 bt1_error ${message.code}")
        }
        return message
    }

    private fun sendPayload(payload: ByteArray) {
        val stream = checkNotNull(output)
        val cipher = encryptor
        if (cipher != null) {
            stream.write(cipher.encryptFrame(payload))
        } else {
            stream.writeInt(payload.size)
            stream.write(payload)
        }
        stream.flush()
    }

    private fun receivePayload(): ByteArray {
        val plain = decryptor ?: return readFrame(RAW_MODE_MAX_PAYLOAD)
        val payload = readFrame(Bt1Frames.MAX_ENCRYPTED_PAYLOAD_LENGTH)
        return plain.tryDecryptPayload(payload)
            ?: throw IOException("bt1 帧解密失败（BT1_DECRYPT_FAILED），连接必须关闭")
    }

    private fun readFrame(maxPayloadLength: Int): ByteArray {
        val stream = checkNotNull(input)
        val prefix = ByteArray(Bt1Frames.LENGTH_PREFIX_LENGTH)
        stream.readFully(prefix)
        val declared = Bt1Frames.readDeclaredPayloadLength(prefix)
        if (declared < 1 || declared > maxPayloadLength) {
            throw IOException("声明的帧长度 $declared 超出 1..$maxPayloadLength")
        }
        val payload = ByteArray(declared.toInt())
        stream.readFully(payload)
        return payload
    }

    private fun BluetoothSocket.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        val PING_PREFIX = "ping ".toByteArray(Charsets.US_ASCII)
        val DATA_PREFIX = "data ".toByteArray(Charsets.US_ASCII)

        const val RTT_ITERATIONS = 50
        const val RTT_PAYLOAD_BYTES = 32
        const val DATA_CHUNK_BYTES = 32 * 1024
        const val RAW_MODE_MAX_PAYLOAD = 8 * 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
