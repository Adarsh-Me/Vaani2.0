package com.itantra.walkie.ml

import android.content.Context
import java.io.File

/**
 * Older builds copied assets/models into filesDir because ONNX Runtime wanted a file
 * path. That made the device hold 812 MB of weights twice - a ~1.6 GB install. Models now
 * stream out of the APK (see Bundled), so this only clears the stale copy on upgrade.
 */
object ModelInstaller {
    fun ensure(context: Context) {
        val base = context.filesDir
        for (d in listOf("models", "refs", "vocabs")) {
            val f = File(base, d)
            if (f.exists()) {
                f.deleteRecursively()
                android.util.Log.e("Walkie", "removed legacy $d copy")
            }
        }
    }
}
