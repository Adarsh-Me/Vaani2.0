package com.itantra.walkie.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * What VANI itself costs this phone, read from the kernel's own counters.
 *
 * The scope is deliberately this process, and the console says so in words. An app cannot read
 * another app's load, so a number labelled "device CPU" here would be a guess dressed as a
 * measurement - which is the one thing this console does not print.
 *
 * Both figures come out of `/proc/self`, because that is what actually works on every device this
 * has run on:
 * - `stat` fields 14 and 15 (utime, stime) sum every thread in the process, so the four ONNX
 *   Runtime intra-op threads show up as the spike an inference really is. `Debug.getRuntimeStat`
 *   was the first attempt and returned nothing on the emulator, which left the meter reading
 *   "measuring" forever - a readout that never fills in is worse than no readout.
 * - `statm` field 1 is resident pages: the same number `dumpsys meminfo` prints as total RSS, so a
 *   reviewer can reproduce it. PSS was the first choice and is not comparable: the 477 MB encoder is
 *   mapped read-only out of the APK, and clean file-backed pages are excluded from PSS, which made
 *   a phone holding 1.1 GB report 100 MB.
 *
 * CPU is smoothed across samples (instantaneous value weighted 0.35). Raw one-second deltas are
 * honest but unreadable on a handset that idles near zero and spends three seconds at full tilt;
 * the smoothed number is still made only of measured samples. The first interval is not shown at
 * all - [ready] stays false until there is one to divide by - because 0% and "not measured yet"
 * are different statements.
 */
class DeviceLoad(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val stat = File("/proc/self/stat")
    private val statm = File("/proc/self/statm")
    private val pageSize = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toInt()
    }.getOrDefault(4096)

    /** Percent of the whole device's capacity, all cores summed. Meaningless before [ready]. */
    var cpu by mutableStateOf(0f); private set

    /** Resident set size of this process, the figure `dumpsys meminfo` agrees with. */
    var ramMb by mutableStateOf(0); private set

    /** High-water mark since the app started: what the model arena actually peaks at. */
    var peakRamMb by mutableStateOf(0); private set

    /** The native heap inside [ramMb] - where ONNX Runtime's arena and the audio buffers live. */
    var nativeMb by mutableStateOf(0); private set

    /** This phone's whole RAM, so the memory bar has a denominator that is not invented. */
    var totalRamMb by mutableStateOf(0); private set

    /** False until one full interval has been measured, so the UI can say "measuring". */
    var ready by mutableStateOf(false); private set

    private var lastTicks = -1L
    private var lastAt = 0L

    init {
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            totalRamMb = (mi.totalMem / 1048576L).toInt()
        }
        scope.launch {
            while (isActive) {
                sample()
                delay(SAMPLE_MS)
            }
        }
    }

    private fun sample() {
        val t = System.nanoTime()
        val ticks = ticks()
        if (ticks != null) {
            if (lastTicks in 1 until ticks) {
                val span = (t - lastAt) / 1e9
                if (span > 0.05) {
                    // USER_HZ is 100 on every Android kernel, so one tick is 10 ms of CPU time.
                    val inst = (ticks - lastTicks) / (100.0 * span * cores) * 100.0
                    cpu = if (ready) (cpu + (inst - cpu) * 0.35f).toFloat() else inst.toFloat()
                    ready = true
                }
            }
            lastTicks = ticks
            lastAt = t
        }
        ramMb = rssMb() ?: ramMb
        nativeMb = (Debug.getNativeHeapAllocatedSize() / 1048576L).toInt()
        if (ramMb > peakRamMb) peakRamMb = ramMb
    }

    /** Process utime + stime in ticks. The comm field can hold spaces and brackets, so the line
     *  is read from after its last ')'. */
    private fun ticks(): Long? = runCatching {
        val line = stat.readText()
        val f = line.substringAfterLast(')').trim().split(' ')
        // After comm: state is field 3, so utime and stime are the 11th and 12th of what remains.
        f[11].toLong() + f[12].toLong()
    }.getOrNull()

    private fun rssMb(): Int? = runCatching {
        // statm: size resident shared text data swp dt, all in pages.
        val resident = statm.readText().trim().split(' ')[1].toLong()
        val mb = resident * pageSize / 1048576L
        if (mb <= 0L) null else mb.toInt()
    }.getOrNull()

    /** How many cores [cpu] is a share of, so the console can say so in words. */
    val coreCount: Int get() = cores

    companion object {
        private const val SAMPLE_MS = 1000L
    }
}
