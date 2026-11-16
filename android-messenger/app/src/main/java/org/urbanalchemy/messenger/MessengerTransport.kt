package org.urbanalchemy.messenger

/**
 * Optional Reticulum/LXMF adapter boundary. The standalone UA peer transport is defined separately.
 *
 * The local database must persist an outbound message as QUEUED before calling [send].
 * Implementations must report protocol status honestly: ROUTER_QUEUED is not SENT, and
 * SENT is not DELIVERED. A DELIVERED update requires a valid LXMF delivery proof.
 *
 * This adapter is deliberately isolated from UA's transport-neutral message and peer-connection layers.
 */
interface ReticulumLxmfTransport {
    interface Listener {
        fun onReady(localDestination: String)
        fun onStatus(update: LxmfTransportUpdate)
        fun onIncoming(message: LxmfIncomingMessage)
        fun onError(error: LxmfLxmfTransportError)
    }

    /** Start the transport and load/create its independent persistent network identity. */
    suspend fun start(listener: Listener)

    /** Announce the local LXMF delivery destination to the Reticulum network. */
    suspend fun announce()

    /** Submit a message which has already been persisted locally. */
    suspend fun send(message: LxmfOutboundMessage)

    /** Stop network activity without deleting identity, router state, or local messages. */
    suspend fun stop()
}

data class LxmfOutboundMessage(
    val localMessageId: String,
    val recipientDestination: String,
    val body: String,
    val createdAtEpochMillis: Long
)

data class LxmfIncomingMessage(
    val transportMessageId: String,
    val senderDestination: String,
    val body: String,
    val receivedAtEpochMillis: Long
)

data class LxmfTransportUpdate(
    val localMessageId: String,
    val state: State,
    val detail: String? = null
) {
    enum class State {
        ROUTER_QUEUED,
        SENT,
        DELIVERED,
        FAILED
    }
}

data class LxmfTransportError(
    val code: String,
    val message: String,
    val recoverable: Boolean
)
