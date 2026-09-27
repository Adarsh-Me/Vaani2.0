package com.itantra.walkie.ml

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Reads the app's own models out of the APK instead of shipping them to filesDir first.
 *
 * There is 1,027 MB of ONNX here, and the APK carries it as 883 MB of Deflate. Copying every
 * model out on first run made the device hold two copies (APK + data dir) and the install
 * measured ~1.6 GB, so the bytes are streamed straight out of the APK into a direct buffer.
 * [mapped] is the one exception: the SraVaani encoder, which cannot fit a private buffer.
 *
 * Sizes come from the APK's zip directory rather than AssetManager, because a compressed
 * asset reports no usable length, and allocateDirect needs one up front.
 */
class Bundled(context: Context) {
    private val assets = context.assets
    private val apkPath = context.applicationInfo.sourceDir
    private val mmapDir = File(context.filesDir, "mmap").apply { mkdirs() }

    /**
     * A real source of bytes for a model too large to hold as a private buffer.
     *
     * `buffer()` copies an asset into native memory and ONNX Runtime reads it from there, which
     * is right for the 20-130 MB graphs and fatal for the 477 MB SraVaani encoder: the process
     * died in allocateDirect with "Failed to allocate a 476501437 byte allocation with 25165824
     * free bytes".
     *
     * Because that entry is stored uncompressed (see `androidResources.noCompress` in
     * build.gradle.kts) it has a byte range inside base.apk, so `openFd` + `FileChannel.map`
     * hands the runtime a read-only mapping of the package the installer already put on disk: no
     * second copy, and the pages stay file-backed and reclaimable rather than private and pinned.
     *
     * A compressed entry has no byte range to point at, so it still extracts once to filesDir and
     * is read by path - a copy, but only ever for a build that dropped the noCompress rule.
     */
    fun mapped(assetPath: String): Large {
        try {
            assets.openFd(assetPath).use { afd ->
                if (afd.length > 0 && afd.startOffset >= 0) {
                    // Closing the channel after map() is safe: the mapping outlives it.
                    FileChannel.open(Paths.get(apkPath), StandardOpenOption.READ).use { ch ->
                        dropStaleExtract(assetPath)
                        return Large.Buffer(
                            ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BUNDLED", "$assetPath is not mappable from the APK (${e.message}); extracting")
        }
        return Large.File(extract(assetPath))
    }

    /**
     * A handset that ran the build before this one carries the extracted encoder in filesDir, and
     * nothing reads it any more - so 477 MB of the user's storage would sit there until uninstall.
     * Only deleted on the mapping path: a build whose asset really is compressed still needs it.
     */
    private fun dropStaleExtract(assetPath: String) {
        val stale = File(mmapDir, assetPath.replace('/', '_'))
        if (stale.exists() && stale.delete())
            android.util.Log.e("BUNDLED", "dropped stale extract ${stale.name} (${stale.length() / 1048576} MB)")
    }

    /** Where a large model's bytes came from, so the caller picks the matching createSession. */
    sealed class Large {
        class Buffer(val buf: ByteBuffer) : Large()
        class File(val file: java.io.File) : Large()
    }

    private fun extract(assetPath: String): File {
        val want = size(assetPath).toLong()
        val out = File(mmapDir, assetPath.replace('/', '_'))
        if (want > 0 && out.length() == want) return out
        val tmp = File(out.path + ".part")
        assets.open(assetPath).use { src -> tmp.outputStream().use { src.copyTo(it, 1 shl 20) } }
        if (want > 0 && tmp.length() != want) throw IOException("short extract of $assetPath")
        if (!out.parentFile!!.exists()) out.parentFile!!.mkdirs()
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
        return out
    }

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
