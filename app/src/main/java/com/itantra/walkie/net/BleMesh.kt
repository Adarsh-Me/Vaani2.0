package com.itantra.walkie.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.itantra.walkie.Lang
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.ArrayDeque

/**
 * The real radio: two handsets with no network between them.
 *
 * Every phone advertises a private GATT service and scans for the same one, so discovery is
 * passive - nobody opens a settings screen or pairs by hand. For each pair the lower-addressed
 * phone initiates the connection and the other accepts, which keeps one link per pair instead of
 * two. A message leaves as fragments sized to the negotiated MTU. A frame that arrives for someone
 * else, or a broadcast not yet seen, is forwarded once with a hop limit - that is what turns two
 * links into a mesh.
 *
 * The sender comes from the link itself, never the header, because at the 23-byte default MTU
 * those bytes are the difference between a message that fits and one that cannot be sent at all.
 */
class BleMesh(
    context: Context,
    /** Read when a link opens, so a name set in Identity is announced without a restart. */
    private val identity: () -> Pair<String, Lang>,
) : MeshTransport {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val appContext = context.applicationContext

    override var radio by mutableStateOf(RadioState.Off)
    override var peers by mutableStateOf(listOf<Peer>())
    override var lastSweepMs by mutableStateOf(0L)
    override var nodesReached by mutableStateOf(0)
    override var listener: ((String, String, String, com.itantra.walkie.ml.Tone) -> Unit)? = null

    private val known = LinkedHashMap<String, Peer>()
    override fun lastKnown(peerId: String): Peer? = known[peerId]

    private var server: BluetoothGattServer? = null
    private var advCb: AdvertiseCallback? = null
    private var scanCb: ScanCallback? = null
    private val links = LinkedHashMap<String, Link>()
    private val inbox = LinkedHashMap<String, Reassembly>()
    private var nextId = 0
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>?): Boolean = size > 256
    }

    private inner class Link(val addr: String) {
        var gatt: BluetoothGatt? = null

        /** True when the peer connected to us, so writes to it go out as notifications. */
        var peripheral = false
        var mtu = 23
        var ready = false
        var busy = false
        val queue = ArrayDeque<ByteArray>()

        /** Bytes of text that fit in one write on this link, worst case (a direct frame). */
        val chunk get() = (mtu - 3 - FULL_HEADER).coerceAtLeast(1)
    }

    private class Reassembly(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        var have = 0
    }

    // ------------------------------------------------------------------ lifecycle

    /** The radio permissions this Android version actually asks for. */
    private fun radioPermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private fun allowed(): Boolean = radioPermissions().all {
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, it
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        val a = manager?.adapter
        if (a == null) { radio = RadioState.RadioMissing; return }
        if (!a.isEnabled) { radio = RadioState.Off; return }
        if (!allowed()) { radio = RadioState.PermissionNeeded; return }
        radio = RadioState.Scanning
        openServer()
        advertise(a)
        scan(a)
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        val a = manager?.adapter
        advCb?.let { runCatching { a?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        scanCb?.let { runCatching { a?.bluetoothLeScanner?.stopScan(it) } }
        advCb = null; scanCb = null
        for (l in links.values) l.gatt?.close()
        links.clear(); inbox.clear()
        runCatching { server?.close() }
        server = null
        peers = emptyList(); nodesReached = 0; radio = RadioState.Off
    }

    private fun openServer() {
        if (server != null) return
        val s = manager?.openGattServer(appContext, serverCb) ?: return
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        s.addService(svc)
        server = s
    }

    @SuppressLint("MissingPermission")
    private fun advertise(a: android.bluetooth.BluetoothAdapter) {
        val adv = a.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        // The display name travels in the handshake, not the advertisement: a scan-response name
        // is capped by the controller, and one string must not have two sources of truth.
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false).setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE)).build()
        advCb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                android.util.Log.i(TAG, "advertising")
            }
            override fun onStartFailure(errorCode: Int) {
                android.util.Log.e(TAG, "advertise failed: $errorCode")
            }
        }
        runCatching { adv.startAdvertising(settings, data, advCb) }
            .onFailure { android.util.Log.e(TAG, "advertiser", it) }
    }

    @SuppressLint("MissingPermission")
    private fun scan(a: android.bluetooth.BluetoothAdapter) {
        val sc = a.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).build()
        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { saw(it) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { saw(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e(TAG, "scan failed: $errorCode")
            }
        }
        runCatching { sc.startScan(listOf(filter), settings, scanCb) }
            .onFailure { android.util.Log.e(TAG, "scanner", it) }
    }

    @SuppressLint("MissingPermission")
    private fun saw(r: ScanResult) {
        val d = r.device ?: return
        heard(d.address, r.rssi)
        if (radio == RadioState.Scanning) radio = RadioState.Live
        val mine = manager?.adapter?.address ?: return
        val l = linkFor(d.address)
        if (l.gatt == null && !l.peripheral && mine < d.address) {
            runCatching { d.connectGatt(appContext, false, gattCb, BluetoothDevice.TRANSPORT_LE) }
                .onFailure { android.util.Log.e(TAG, "connect", it) }
        }
    }

    /** Any contact with a node - a scan hit, a frame, a link event - keeps it on the board. */
    private fun heard(addr: String, rssi: Int = -1) {
        val now = System.currentTimeMillis()
        val old = known[addr]
        known[addr] = (old ?: Peer(addr, "", Lang.HI, rssi, now))
            .copy(lastHeardMs = now, rssi = if (rssi == -1) old?.rssi ?: -1 else rssi)
        publish()
    }

    // ------------------------------------------------------------------ sending

    override fun reachable(addr: Address): Boolean = when (addr) {
        is Address.All -> links.values.any { it.ready }
        is Address.One -> links[addr.peerId]?.ready == true
    }

    @SuppressLint("MissingPermission")
    override fun send(addr: Address, text: String, tone: com.itantra.walkie.ml.Tone): Boolean {
        // The tone rides in front of the message body, not in the PDU header: the header is
        // already full and the relay forwards it untouched, so a three-byte prefix is the only
        // place it can go without changing how a frame is routed.
        val bytes = com.itantra.walkie.ml.Tone.pack(text, tone)
        if (bytes.isEmpty()) return false
        val live = links.values.filter { it.ready }
        if (live.isEmpty()) return false
        val max = live.minOf { it.chunk }
        val count = ((bytes.size + max - 1) / max).coerceAtMost(255)
        if (bytes.size > count * max) return false
        val id = nextId
        nextId = (nextId + 1) % 65536
        val direct = addr is Address.One
        val target = if (direct) macBytes((addr as Address.One).peerId) else null
        if (direct && target == null) return false
        var off = 0
        for (i in 0 until count) {
            val n = max.coerceAtMost(bytes.size - off)
            val pdu = ByteArrayOutputStream()
            DataOutputStream(pdu).use { o ->
                o.writeByte(TYPE_DATA or (if (direct) FLAG_DIRECT else 0) or (MAX_HOPS shl HOPS_SHIFT))
                o.writeShort(id)
                o.writeByte(i)
                o.writeByte(count)
                if (target != null) o.write(target)
                o.write(bytes, off, n)
            }
            off += n
            val frame = pdu.toByteArray()
            for (l in live) { l.queue.add(frame); pump(l) }
        }
        return true
    }

    private fun pump(l: Link) {
        if (l.busy || !l.ready) return
        val b = l.queue.poll() ?: return
        l.busy = true
        if (!writeOut(l, b)) { l.busy = false; l.queue.add(b) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeOut(l: Link, b: ByteArray): Boolean {
        return if (l.peripheral) {
            val s = server ?: return false
            val device = manager?.adapter?.getRemoteDevice(l.addr) ?: return false
            val ch = s.getService(SERVICE)?.getCharacteristic(TX) ?: return false
            ch.setValue(b)
            runCatching { s.notifyCharacteristicChanged(device, ch, false) }.getOrDefault(false)
        } else {
            val g = l.gatt ?: return false
            val ch = g.getService(SERVICE)?.getCharacteristic(RX) ?: return false
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.setValue(b)
            runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
        }
    }

    // ------------------------------------------------------------------ receiving

    private fun onBytes(addr: String, raw: ByteArray) {
        if (raw.isEmpty()) return
        heard(addr)
        when (raw[0].toInt() and 0x0F) {
            TYPE_HELLO -> onHello(addr, raw)
            TYPE_DATA -> onData(addr, raw)
        }
    }

    private fun onHello(addr: String, raw: ByteArray) {
        if (raw.size < 2) return
        val rest = raw.copyOfRange(1, raw.size)
        val split = rest.indexOf(0.toByte())
        if (split < 0) return
        val name = String(rest.copyOfRange(0, split), Charsets.UTF_8)
        val lang = Lang.values().firstOrNull {
            it.tag == String(rest.copyOfRange(split + 1, rest.size), Charsets.UTF_8)
        } ?: Lang.HI
        heard(addr)
        known[addr] = (known[addr] ?: Peer(addr, name, lang, -60, System.currentTimeMillis()))
            .copy(name = name, lang = lang)
        publish()
    }

    private fun onData(addr: String, raw: ByteArray) {
        val flags = raw[0].toInt() and 0xFF
        val hops = (flags shr HOPS_SHIFT) and 0x07
        val direct = (flags and FLAG_DIRECT) != 0
        if (raw.size < (if (direct) FULL_HEADER else MIN_HEADER)) return
        val id = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        val idx = raw[3].toInt() and 0xFF
        val count = raw[4].toInt() and 0xFF
        if (count == 0 || idx >= count) return
        var p = MIN_HEADER
        val dest: String
        if (direct) {
            dest = formatMac(raw.copyOfRange(p, p + 6)); p += 6
        } else dest = Address.ALL_ID
        val key = "$addr:$id"
        val r = inbox.getOrPut(key) { Reassembly(count) }
        if (r.parts[idx] == null) {
            r.parts[idx] = raw.copyOfRange(p, raw.size); r.have++
        }
        if (r.have == count) {
            inbox.remove(key)
            val me = manager?.adapter?.address
            if (!direct || dest == me || me == null) {
                val (tone, body) = com.itantra.walkie.ml.Tone.unpack(r.join())
                listener?.invoke(
                    addr, if (direct) dest else Address.ALL_ID, body, tone
                )
            }
        }
        if (hops > 0 && seen.put(key, true) == null) {
            val fwd = raw.copyOf()
            // Keep the type and addressing bits, spend one hop.
            fwd[0] = ((flags and 0x1F) or ((hops - 1) shl HOPS_SHIFT)).toByte()
            for (l in links.values) if (l.addr != addr && l.ready) { l.queue.add(fwd); pump(l) }
        }
    }

    private fun Reassembly.join(): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p ?: ByteArray(0))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ gatt plumbing

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val addr = device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                linkFor(addr).peripheral = true
                heard(addr)
                hello(addr)
            } else dropLink(addr)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val addr = device?.address ?: return
            if (characteristic?.uuid == RX && value != null) onBytes(addr, value)
            if (responseNeeded) runCatching {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            device?.let { linkFor(it.address).mtu = mtu }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            device?.let { val l = linkFor(it.address); l.busy = false; pump(l) }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            val addr = g?.device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                linkFor(addr).gatt = g
                runCatching { g.requestMtu(517) }
                runCatching { g.discoverServices() }
            } else {
                runCatching { g.close() }
                dropLink(addr)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val l = linkFor(g.device.address)
            l.ready = true
            val ch = g.getService(SERVICE)?.getCharacteristic(TX)
            if (ch != null) {
                runCatching { g.setCharacteristicNotification(ch, true) }
                ch.getDescriptor(CCCD)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    runCatching { g.writeDescriptor(it) }
                }
            }
            hello(g.device.address)
            pump(l)
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            g?.device?.let { linkFor(it.address).mtu = mtu }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == TX) onBytes(g.device.address, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt?, ch: BluetoothGattCharacteristic?, status: Int
        ) {
            g?.device?.let { val l = linkFor(it.address); l.busy = false; pump(l) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt?, d: BluetoothGattDescriptor?, status: Int
        ) {
            g?.device?.let { pump(linkFor(it.address)) }
        }
    }

    private fun hello(addr: String) {
        val (name, lang) = identity()
        val pdu = ByteArrayOutputStream()
        DataOutputStream(pdu).use {
            it.writeByte(TYPE_HELLO)
            it.write(name.toByteArray(Charsets.UTF_8))
            it.writeByte(0)
            it.write(lang.tag.toByteArray(Charsets.UTF_8))
        }
        val l = linkFor(addr)
        l.queue.add(pdu.toByteArray()); l.ready = true; pump(l)
    }

    private fun linkFor(addr: String): Link = links.getOrPut(addr) { Link(addr) }

    private fun dropLink(addr: String) {
        links.remove(addr)?.gatt = null
        heard(addr)
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val live = known.values.filter { now - it.lastHeardMs < LISTEN_MS && it.name.isNotBlank() }
        peers = live.sortedWith(compareByDescending<Peer> { it.bars }.thenBy { it.name })
        nodesReached = live.size
    }

    companion object {
        private const val TAG = "BLEMESH"
        val SERVICE: java.util.UUID = java.util.UUID.fromString("0a4b3c2d-1e5f-4a6b-8c7d-9e0f1a2b3c4d")
        val RX: java.util.UUID = java.util.UUID.fromString("0a4b3c2d-1e5f-4a6b-8c7d-9e0f1a2b3c4e")
        val TX: java.util.UUID = java.util.UUID.fromString("0a4b3c2d-1e5f-4a6b-8c7d-9e0f1a2b3c4f")
        private val CCCD: java.util.UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TYPE_HELLO = 1
        private const val TYPE_DATA = 2
        private const val FLAG_DIRECT = 0x10
        private const val HOPS_SHIFT = 5
        private const val MAX_HOPS = 3

        /** [flags][id 2][idx][count] plus, for a direct frame, the 6-byte target address. */
        private const val MIN_HEADER = 5
        private const val FULL_HEADER = 11

        private const val LISTEN_MS = 20_000L

        private fun macBytes(addr: String): ByteArray? {
            val parts = addr.split(":")
            if (parts.size != 6) return null
            return runCatching { ByteArray(6) { parts[it].toInt(16).toByte() } }.getOrNull()
        }

        private fun formatMac(b: ByteArray): String = b.joinToString(":") { String.format("%02X", it) }
    }
}
