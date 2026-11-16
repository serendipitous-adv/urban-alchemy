package org.urbanalchemy.messenger

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Small transport-agnostic state machine for exchanging signed session offers.
 * The caller supplies an already-authenticated/pinned peer signing key and a
 * frame sender (normally SocketFrameTransport.send). No peer private key is
 * ever accepted or exposed here.
 */
class PeerSessionHandshakeController(
    private val localSigningKeyPair: KeyPair,
    private val pinnedPeerSigningKey: PublicKey,
    private val replayGuard: SessionReplayGuard,
    private val sendFrame: (ByteArray) -> Unit,
    private val onEstablished: (AuthenticatedSessionEstablisher.EstablishedSession) -> Unit,
    private val onError: (String) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val createEphemeralKeyPair: () -> KeyPair = ::newEphemeralKeyPair
) : AutoCloseable {

    enum class State { NEW, WAITING_FOR_PEER, ESTABLISHED, FAILED, CLOSED }

    private val localFingerprint = SignedSessionHandshake.fingerprint(localSigningKeyPair.public)
    private val peerFingerprint = SignedSessionHandshake.fingerprint(pinnedPeerSigningKey)
    private var state = State.NEW
    private var localOffer: SignedSessionHandshake.Offer? = null
    private var localEphemeral: KeyPair? = null
    private var session: AuthenticatedSessionEstablisher.EstablishedSession? = null
    private var localIsInitiator = false

    @Synchronized fun state(): State = state

    /** Begin as initiator. The peer signing key must already be pinned out of band. */
    @Synchronized
    fun begin() {
        check(state == State.NEW) { "Session handshake can only begin once." }
        require(localFingerprint != peerFingerprint) { "Cannot establish a session with the same identity." }
        localIsInitiator = true
        val now = nowMillis()
        val ephemeral = createEphemeralKeyPair()
        val offer = SignedSessionHandshake.createOffer(
            UUID.randomUUID().toString(), localFingerprint, peerFingerprint,
            ephemeral, localSigningKeyPair, ByteArray(32).also(SecureRandom()::nextBytes),
            now, safeAdd(now, SignedSessionHandshake.DEFAULT_MAX_LIFETIME_MILLIS)
        )
        localEphemeral = ephemeral
        localOffer = offer
        state = State.WAITING_FOR_PEER
        try { sendOffer(offer) } catch (error: Exception) { fail(error) }
    }

    /** Feed one complete UA protocol frame received from the connected peer. */
    @Synchronized
    fun receiveFrame(encodedFrame: ByteArray) {
        check(state != State.CLOSED && state != State.FAILED) { "Handshake is not active." }
        try {
            val frame = UaProtocolFrame.decode(encodedFrame)
            require(frame.type == UaProtocolFrame.Type.SESSION_OFFER) {
                "Expected a signed session offer frame."
            }
            val remoteOffer = SignedSessionOfferCodec.decode(frame.payload)
            when (state) {
                State.NEW -> receiveAsResponder(remoteOffer)
                State.WAITING_FOR_PEER -> receiveAsInitiator(remoteOffer)
                State.ESTABLISHED -> error("A session is already established.")
                State.FAILED, State.CLOSED -> error("Handshake is not active.")
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    @Synchronized
    fun establishedSession(): AuthenticatedSessionEstablisher.EstablishedSession? = session

    private fun receiveAsResponder(remoteOffer: SignedSessionHandshake.Offer) {
        require(remoteOffer.recipientFingerprint == localFingerprint) { "Offer is addressed to another identity." }
        require(remoteOffer.senderFingerprint == peerFingerprint) { "Offer sender is not the pinned peer." }
        require(remoteOffer.sessionId.isNotBlank())
        // Verify the initiator before creating or sending a response.
        SignedSessionHandshake.verifyOffer(
            remoteOffer, peerFingerprint, localFingerprint,
            pinnedPeerSigningKey, nowMillis()
        )
        localIsInitiator = false
        val now = nowMillis()
        val ephemeral = createEphemeralKeyPair()
        val response = SignedSessionHandshake.createOffer(
            remoteOffer.sessionId, localFingerprint, peerFingerprint,
            ephemeral, localSigningKeyPair, ByteArray(32).also(SecureRandom()::nextBytes),
            now, safeAdd(now, SignedSessionHandshake.DEFAULT_MAX_LIFETIME_MILLIS)
        )
        localEphemeral = ephemeral
        localOffer = response
        state = State.WAITING_FOR_PEER
        sendOffer(response)
        establish(remoteOffer, response)
    }

    private fun receiveAsInitiator(remoteOffer: SignedSessionHandshake.Offer) {
        val own = requireNotNull(localOffer) { "Local offer is missing." }
        require(remoteOffer.sessionId == own.sessionId) { "Peer responded to another session." }
        require(remoteOffer.senderFingerprint == peerFingerprint) { "Offer sender is not the pinned peer." }
        require(remoteOffer.recipientFingerprint == localFingerprint) { "Offer is addressed to another identity." }
        establish(own, remoteOffer)
    }

    private fun establish(firstLocalOrRemote: SignedSessionHandshake.Offer, secondLocalOrRemote: SignedSessionHandshake.Offer) {
        val own = requireNotNull(localOffer)
        val remote = if (firstLocalOrRemote === own || firstLocalOrRemote.sessionId == own.sessionId &&
            firstLocalOrRemote.senderFingerprint == own.senderFingerprint) secondLocalOrRemote else firstLocalOrRemote
        val initiatorOffer = if (localIsInitiator) own else remote
        val responderOffer = if (localIsInitiator) remote else own
        val established = AuthenticatedSessionEstablisher(replayGuard).establish(
            initiatorOffer, responderOffer,
            if (localIsInitiator) localSigningKeyPair.public else pinnedPeerSigningKey,
            if (localIsInitiator) pinnedPeerSigningKey else localSigningKeyPair.public,
            requireNotNull(localEphemeral),
            nowMillis(),
            localIsInitiator
        )
        session = established
        localEphemeral = null // Release the ephemeral private-key handle after ECDH derivation.
        state = State.ESTABLISHED
        onEstablished(established)
    }

    private fun sendOffer(offer: SignedSessionHandshake.Offer) {
        val payload = SignedSessionOfferCodec.encode(offer)
        sendFrame(UaProtocolFrame(UaProtocolFrame.Type.SESSION_OFFER, payload).encode())
    }

    private fun fail(error: Exception) {
        state = State.FAILED
        localEphemeral = null
        session?.close()
        session = null
        onError(error.message ?: error.javaClass.simpleName)
    }

    @Synchronized
    override fun close() {
        if (state == State.CLOSED) return
        session?.close()
        session = null
        localEphemeral = null
        state = State.CLOSED
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment

    companion object {
        private fun newEphemeralKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
    }
}
