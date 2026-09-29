package com.itantra.walkie.net

import com.itantra.walkie.Lang
import com.itantra.walkie.ml.Tone

/** What the physical radio is doing, as the hardware reports it. */
enum class RadioState { Off, Scanning, Live, RadioMissing, PermissionNeeded }

/** How a transmission is addressed: one device, or every device in the mesh at once. */
sealed class Address {
    object All : Address() { override fun toString() = "ALL" }
    data class One(val peerId: String) : Address()

    companion object {
        /** The broadcast channel's stable id, used as a thread key too. */
        const val ALL_ID = "ALL"
        fun of(peerId: String): Address = if (peerId == ALL_ID) All else One(peerId)
    }
}

/**
 * A device in the mesh. [id] is the BLE address, which is the only stable handle the radio gives;
 * [name] and [lang] arrive in the handshake, so a node discovered but not yet introduced shows
 * with its address until it does.
 */
data class Peer(
    val id: String,
    val name: String,
    val lang: Lang,
    val rssi: Int,
    val lastHeardMs: Long,
    /** Where it said it was, from its own GPS. Null until a position frame arrives. */
    val pos: Fix? = null,
) {
    /** 0..4 bands, from the one threshold table the console's meter uses. */
    val bars: Int get() = Proximity.band(rssi)
}

/**
 * The transport seam. [BleMesh] is the implementation the app ships with; the interface exists so
 * a different radio - BLE mesh proper, Wi-Fi Direct - can be swapped in without touching `ui/`.
 * An implementation must publish the four observable properties, answer start/stop, report whether
 * an address has a live link, and call [listener] for every message that arrives off the air.
 */
interface MeshTransport {
    var radio: RadioState
    var peers: List<Peer>
    var lastSweepMs: Long
    var nodesReached: Int

    /** A node that was in range and is not any more: the console still has to name its channel. */
    fun lastKnown(peerId: String): Peer?

    /** Whether a transmission to this address has a live link to travel on right now. */
    fun reachable(addr: Address): Boolean

    /**
     * The rolling signal samples for one peer, oldest first, newest last. Everything the console says
     * about nearness - the bar fill, the direction of travel - is computed from these by
     * [Proximity], which is where the honest limits of RSSI are written down. Never metres.
     *
     * Default is empty so a transport that keeps no signal history cannot accidentally claim a
     * reading it does not have: the bar stays flat and the arrow stays quiet.
     */
    fun proximitySamples(addr: String): List<Int> = emptyList()

    fun start()
    fun stop()

    /**
     * @param tone how the sender's voice sounded while the words were said. A neutral tone adds
     * nothing to the frame, so an ordinary transmission is byte-for-byte what it always was.
     * @param hops how far this message may be relayed. Left at the default for ordinary traffic;
     * a distress beacon is the one case where spending more of the shared air is worth it.
     * @return the number of bytes the text became on air - body plus tone framing, before
     * fragmentation - or 0 when the frame could not be handed to the radio at all. The console
     * prints this, so it is the count of bytes that actually left, not the length of the text.
     */
    fun send(addr: Address, text: String, tone: Tone = Tone.NEUTRAL, hops: Int = 0): Int

    /**
     * Put this handset's own position on every live link. Never relayed: a position is a
     * first-person statement, and a forwarded one is somebody else's guess about where a stranger
     * stands. Empty bytes means sharing is off, and the peer's stale fix is dropped.
     */
    fun reportPosition(encoded: ByteArray)

    /**
     * Set by the app: invoked for inbound traffic with the sending node's id, the channel the
     * frame belongs on - [Address.ALL_ID] for a broadcast, this phone's own address for a direct
     * one - the tone that arrived with it (neutral when the sender sent none), and the size of the
     * frame it came in. A broadcast has to land on the broadcast channel, not in a private thread.
     */
    var listener:
        ((fromPeerId: String, channelId: String, text: String, tone: Tone, onAirBytes: Int) -> Unit)?

    /**
     * Fired when the count of handsets in range changes, and only then. The foreground service
     * restates it in the shade, so "is it still listening?" can be answered without unlocking the
     * phone - which is the only way a radio carried in a pocket stays trustworthy.
     */
    var onRoster: ((Int) -> Unit)?
}

