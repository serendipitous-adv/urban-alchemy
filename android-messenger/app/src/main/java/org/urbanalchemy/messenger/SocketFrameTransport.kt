package org.urbanalchemy.messenger

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/**
 * Transport-neutral byte channel for one already-connected peer socket.
 *
 * Payloads are UA protocol frames (for example, signed session offers, encrypted message
 * envelopes, or signed delivery receipts). This layer deliberately knows nothing about
 * LXMF, Reticulum, contact addresses, encryption keys, or delivery semantics.
 *
 * A Wi-Fi Direct or local-network adapter can establish the Socket and hand it here.
 * A future Reticulum adapter can carry the same UA frames without changing message logic.
 */
class SocketFrameTransport(
    private val socket: Socket,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onFrame(frame: ByteArray)
        fun onDisconnected()
        fun onError(error: IOException)
    }

    @Volatile
    private var closed = false
    private val outputLock = Any()
    private var readerThread: Thread? = null

    fun start() {
        check(readerThread == null) { "Transport has already been started." }
        check(!closed) { "Transport is closed." }
        socket.tcpNoDelay = true
        readerThread = Thread({ readLoop() }, "ua-peer-socket-reader").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Sends one complete frame. Call from a background thread; writes are serialized
     * so concurrent producers cannot interleave frame headers and payloads.
     */
    @Throws(IOException::class)
    fun send(frame: ByteArray) {
        require(frame.isNotEmpty() && frame.size <= MAX_FRAME_BYTES) { "Invalid peer frame size." }
        synchronized(outputLock) {
            check(!closed) { "Transport is closed." }
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }
    }

    private fun readLoop() {
        var failure: IOException? = null
        try {
            val input = DataInputStream(socket.getInputStream())
            while (!closed) {
                val length = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (length !in 1..MAX_FRAME_BYTES) {
                    throw IOException("Peer frame length is outside the allowed range.")
                }
                val frame = ByteArray(length)
                input.readFully(frame)
                if (!closed) listener.onFrame(frame)
            }
        } catch (error: IOException) {
            if (!closed) failure = error
        } finally {
            closeSocket()
            if (failure != null) listener.onError(failure)
            listener.onDisconnected()
        }
    }

    private fun closeSocket() {
        if (!closed) closed = true
        try {
            socket.close()
        } catch (_: IOException) {
            // Closing is best-effort; the connection is already considered unavailable.
        }
    }

    override fun close() {
        closeSocket()
    }

    companion object {
        const val MAX_FRAME_BYTES = 1_048_576
    }
}
