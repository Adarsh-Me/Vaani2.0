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
) {
    /** 0..4 bars. Real hardware reports roughly -50 dBm next hand to -95 dBm at range. */
    val bars: Int get() = when {
        rssi >= -61 -> 4
        rssi >= -71 -> 3
        rssi >= -81 -> 2
        rssi >= -91 -> 1
        else -> 0
    }
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

    fun start()
    fun stop()

    /**
     * @param tone how the sender's voice sounded while the words were said. A neutral tone adds
     * nothing to the frame, so an ordinary transmission is byte-for-byte what it always was.
     * @return false when the frame could not be handed to the radio at all.
     */
    fun send(addr: Address, text: String, tone: Tone = Tone.NEUTRAL): Boolean

    /**
     * Set by the app: invoked for inbound traffic with the sending node's id, the channel the
     * frame belongs on - [Address.ALL_ID] for a broadcast, this phone's own address for a direct
     * one - and the tone that arrived with it, neutral when the sender sent none. A broadcast has
     * to land on the broadcast channel, not in a private thread.
     */
    var listener: ((fromPeerId: String, channelId: String, text: String, tone: Tone) -> Unit)?
}

