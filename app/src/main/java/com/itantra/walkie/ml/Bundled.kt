package com.itantra.walkie.ml

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Reads the app's own assets without ever copying them to filesDir.
 *
 * The models are 812 MB of ONNX; copying them out on first run meant the device held
 * two copies (APK + data dir) and the install measured ~1.6 GB. ONNX Runtime's Java API
 * takes a direct ByteBuffer, so the bytes can be streamed straight out of the APK.
 *
 * Sizes come from the APK's zip directory rather than AssetManager, because a compressed
 * asset reports no usable length, and allocateDirect needs one up front.
 */
class Bundled(context: Context) {
    private val assets = context.assets
    private val apkPath = context.applicationInfo.sourceDir

    /** Model bytes as a direct buffer, ready for OrtEnvironment.createSession. */
    fun buffer(assetPath: String): ByteBuffer {
        // Direct (native) memory only: the Dalvik heap is capped at ~192 MB and a model
        // byte[] there is an instant OOM.
        val ins = assets.open(assetPath)
        try {
            val hint = size(assetPath)
            var buf = ByteBuffer.allocateDirect(if (hint > 0) hint else 4 shl 20)
            val chunk = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                if (buf.remaining() < n) {
                    var c = buf.capacity() * 2
                    while (c - buf.position() < n) c *= 2
                    val grown = ByteBuffer.allocateDirect(c)
                    buf.flip()
                    grown.put(buf)
                    buf = grown
                }
                buf.put(chunk, 0, n)
            }
            buf.flip()
            return buf
        } finally {
            ins.close()
        }
    }

    fun bytes(assetPath: String): ByteArray {
        val hint = size(assetPath)
        return ByteArrayOutputStream(if (hint > 0) hint else 1024).use { out ->
            assets.open(assetPath).use { it.copyTo(out) }
            out.toByteArray()
        }
    }

    fun text(assetPath: String): String = String(bytes(assetPath), Charsets.UTF_8)

    fun exists(assetPath: String): Boolean = try {
        assets.open(assetPath).close(); true
    } catch (_: Exception) { false }

    /** One pass over the APK central directory; entries are keyed without the assets/ prefix. */
    private val sizes: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        try {
            ZipFile(apkPath).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (!e.isDirectory && e.name.startsWith("assets/")) m[e.name.removePrefix("assets/")] = e.size.toInt()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BUNDLED", "central directory of $apkPath unreadable: ${e.message}")
        }
        m
    }

    /** Uncompressed entry length, or -1 if the APK could not be read as a zip. */
    private fun size(assetPath: String): Int = sizes[assetPath] ?: -1
}
