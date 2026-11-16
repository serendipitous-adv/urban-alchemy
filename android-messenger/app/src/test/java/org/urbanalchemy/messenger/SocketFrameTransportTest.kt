package org.urbanalchemy.messenger

import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class SocketFrameTransportTest {
    private class RecordingListener : SocketFrameTransport.Listener {
        val received = AtomicReference<ByteArray?>()
        val error = AtomicReference<java.io.IOException?>()
        val frameLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)
        val disconnectedLatch = CountDownLatch(1)

        override fun onFrame(frame: ByteArray) {
            received.set(frame.copyOf())
            frameLatch.countDown()
        }

        override fun onDisconnected() {
            disconnectedLatch.countDown()
        }

        override fun onError(error: java.io.IOException) {
            this.error.set(error)
            errorLatch.countDown()
        }
    }

    @Test fun connectedSocketsExchangeCompleteFrames() {
        ServerSocket(0).use { server ->
            val clientSocket = Socket("127.0.0.1", server.localPort)
            val serverSocket = server.accept()
            val clientListener = RecordingListener()
            val serverListener = RecordingListener()
            val client = SocketFrameTransport(clientSocket, clientListener)
            val peer = SocketFrameTransport(serverSocket, serverListener)
            try {
                client.start()
                peer.start()
                val expected = byteArrayOf(1, 2, 3, 4, 5)
                client.send(expected)
                assert(clientListener.received.get() == null)
                org.junit.Assert.assertTrue(serverListener.frameLatch.await(2, TimeUnit.SECONDS))
                assertArrayEquals(expected, serverListener.received.get())

                val response = byteArrayOf(9, 8, 7)
                peer.send(response)
                org.junit.Assert.assertTrue(clientListener.frameLatch.await(2, TimeUnit.SECONDS))
                assertArrayEquals(response, clientListener.received.get())
            } finally {
                client.close()
                peer.close()
            }
        }
    }

    @Test fun rejectsEmptyAndOversizedOutboundFrames() {
        ServerSocket(0).use { server ->
            val clientSocket = Socket("127.0.0.1", server.localPort)
            val serverSocket = server.accept()
            val transport = SocketFrameTransport(clientSocket, RecordingListener())
            try {
                try {
                    transport.send(byteArrayOf())
                    fail("Expected empty frame rejection")
                } catch (_: IllegalArgumentException) { }
                try {
                    transport.send(ByteArray(SocketFrameTransport.MAX_FRAME_BYTES + 1))
                    fail("Expected oversized frame rejection")
                } catch (_: IllegalArgumentException) { }
            } finally {
                transport.close()
                serverSocket.close()
            }
        }
    }

    @Test fun rejectsTruncatedInboundFrameHeader() {
        ServerSocket(0).use { server ->
            val remote = Socket("127.0.0.1", server.localPort)
            val local = server.accept()
            val listener = RecordingListener()
            val transport = SocketFrameTransport(local, listener)
            try {
                transport.start()
                DataOutputStream(remote.getOutputStream()).apply {
                    writeShort(1)
                    flush()
                }
                remote.shutdownOutput()
                org.junit.Assert.assertTrue(listener.errorLatch.await(2, TimeUnit.SECONDS))
                assertNotNull(listener.error.get())
            } finally {
                transport.close()
                remote.close()
            }
        }
    }

    @Test fun rejectsInvalidInboundFrameLength() {
        ServerSocket(0).use { server ->
            val remote = Socket("127.0.0.1", server.localPort)
            val local = server.accept()
            val listener = RecordingListener()
            val transport = SocketFrameTransport(local, listener)
            try {
                transport.start()
                DataOutputStream(remote.getOutputStream()).apply {
                    writeInt(SocketFrameTransport.MAX_FRAME_BYTES + 1)
                    flush()
                }
                org.junit.Assert.assertTrue(listener.errorLatch.await(2, TimeUnit.SECONDS))
                assertNotNull(listener.error.get())
            } finally {
                transport.close()
                remote.close()
            }
        }
    }
}
