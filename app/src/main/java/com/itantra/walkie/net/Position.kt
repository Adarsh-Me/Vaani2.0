package com.itantra.walkie.net

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a handset actually is, and how sure it is about that.
 *
 * This is the one number in the product a phone can produce with no network at all: the GNSS
 * receiver works off satellites, and in a flood that has taken the towers down it still works.
 * Coordinates are what a boat needs. RSSI is not.
 *
 * A fix travels as integer millionths of a degree - ~0.11 m of resolution, 8 bytes, far finer than
 * a phone in a pocket can actually achieve and far coarser than a rescue needs. Accuracy and age
 * ride along, because a fix from four minutes ago and a 80-m-error fix in a hotel lobby are not the
 * same fact, and the console has to be able to tell them apart.
 */
data class Fix(
    val latE6: Int,
    val lonE6: Int,
    val accM: Int,
    val atMs: Long,
) {
    val latDegrees get() = latE6 / 1e6
    val lonDegrees get() = lonE6 / 1e6

    /** How stale this is right now, in seconds - the number the UI shows instead of a bare dot. */
    fun ageS(now: Long = System.currentTimeMillis()) = ((now - atMs) / 1000L).coerceAtLeast(0L)

    /**
     * Great-circle metres to another fix. Exact enough to two decimal places at these distances
     * and cheap enough to run on every roster refresh; this is a real ground distance, which is
     * why it is allowed on screen where an RSSI-derived "12 m" is not.
     */
    fun distanceM(o: Fix): Double {
        val dLat = Math.toRadians(o.latDegrees - latDegrees)
        val dLon = Math.toRadians(o.lonDegrees - lonDegrees)
        val la = Math.toRadians(latDegrees)
        val lb = Math.toRadians(o.latDegrees)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(la) * cos(lb) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * kotlin.math.atan2(sqrt(h), sqrt(1 - h))
    }

    /** The wire form: [lat:4][lon:4][accuracy m:1][age s:2], 11 bytes, big-endian. */
    fun encode(ageS: Int): ByteArray {
        val b = ByteArray(11)
        put(b, 0, latE6); put(b, 4, lonE6)
        b[8] = accM.coerceIn(0, 255).toByte()
        val a = ageS.coerceIn(0, 65535)
        b[9] = ((a ushr 8) and 0xFF).toByte(); b[10] = (a and 0xFF).toByte()
        return b
    }

    companion object {
        private const val EARTH_R = 6371008.8

        fun of(l: Location): Fix = Fix(
            latE6 = (l.latitude * 1e6).toInt(),
            lonE6 = (l.longitude * 1e6).toInt(),
            accM = if (l.hasAccuracy()) l.accuracy.toInt() else 255,
            atMs = l.time,
        )

        /** Null unless the bytes are a complete, sane position - a stale buffer must not invent one. */
        fun decode(b: ByteArray): Fix? {
            if (b.size < 11) return null
            val lat = read(b, 0); val lon = read(b, 4)
            if (lat == 0 && lon == 0) return null
            if (lat !in -90_000_000..90_000_000 || lon !in -180_000_000..180_000_000) return null
            val age = ((b[9].toInt() and 0xFF) shl 8) or (b[10].toInt() and 0xFF)
            return Fix(lat, lon, b[8].toInt() and 0xFF, System.currentTimeMillis() - age * 1000L)
        }

        private fun put(dst: ByteArray, at: Int, v: Int) {
            dst[at] = ((v ushr 24) and 0xFF).toByte()
            dst[at + 1] = ((v ushr 16) and 0xFF).toByte()
            dst[at + 2] = ((v ushr 8) and 0xFF).toByte()
            dst[at + 3] = (v and 0xFF).toByte()
        }

        private fun read(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }
}

/**
 * The GPS reader. One instance, owned by the ViewModel, listening only while the app is alive:
 * background location is a permission this app does not ask for, and a fix that has aged out is
 * labelled with its age rather than quietly reused.
 *
 * GPS provider only, never network geolocation - there is no network, and a phone that reports a
 * cached tower estimate as its position is worse than one that reports nothing.
 */
class FixSource(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Null until the receiver has a real fix. The UI must say "no fix yet", never a stale dot. */
    var fix by mutableStateOf<Fix?>(null); private set

    /** Why there is no fix, in the words the console prints. */
    var problem by mutableStateOf(""); private set

    private var listener: android.location.LocationListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val m = lm ?: run { problem = "no location service on this phone"; return }
        if (listener != null) return
        if (!m.hasProvider(LocationManager.GPS_PROVIDER)) {
            problem = "this phone has no GPS receiver"
            return
        }
        val l = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                fix = Fix.of(location)
                problem = ""
            }
            @Deprecated("Old platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        listener = l
        // A fresh cached fix first so the console is not blank for the 20 s a cold start takes;
        // its own timestamp is what says how trustworthy it is.
        runCatching { m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { fix = Fix.of(it) } }
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, l) }
            .onFailure { problem = it.message ?: "location not available" }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { lm?.removeUpdates(l) }
        listener = null
    }
}
