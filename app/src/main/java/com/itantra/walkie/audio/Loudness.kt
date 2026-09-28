package com.itantra.walkie.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Keeping the handset loud enough to be heard outdoors, without ever overriding the person holding
 * it.
 *
 * A flood console is used in the open, over rain and generator noise, and the reply being spoken
 * is the whole point of the device - a line nobody hears is a line that was never sent. So the
 * media stream is raised to a floor once at start-up and before an incoming line is spoken.
 *
 * The moment the user moves the volume themselves, this stops. That is not politeness, it is the
 * difference between a tool and a broken one: someone who turns it down has a reason - a child
 * asleep under a wet roof, a handset against an ear - and an app that puts the volume back every
 * time it speaks will be uninstalled inside a minute. The observer below is what makes the
 * surrender real rather than a comment claiming it.
 */
object Loudness {

    /** Fraction of the media stream's maximum. Not full scale: max on a small speaker is distortion. */
    private const val FLOOR = 0.75f

    @Volatile private var userAdjusted = false

    private fun am(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Raises the stream to the floor unless the user has already set their own level. */
    fun ensure(context: Context, enabled: Boolean) {
        if (!enabled || userAdjusted) return
        val a = am(context) ?: return
        runCatching {
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val want = (max * FLOOR).toInt().coerceAtLeast(1)
            if (a.getStreamVolume(AudioManager.STREAM_MUSIC) < want)
                a.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0)
        }
    }

    /** True while this handset is still obeying the user's own volume. */
    fun yielded(): Boolean = userAdjusted

    /**
     * Watches the system's own volume setting rather than guessing from key events, because the
     * hardware keys, the slider in the shade, and the settings screen all move the same value.
     */
    fun watchForUser(context: Context, onYield: () -> Unit) {
        val resolver = context.applicationContext.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (selfChange) return   // our own setStreamVolume, not the user reaching over
                if (userAdjusted) return
                userAdjusted = true
                onYield()
            }
        }
        runCatching {
            // The setting's name, not its constant: STREAM_MUSIC_VOLUME was removed from the
            // public surface, while the value the slider writes is still this one.
            resolver.registerContentObserver(
                Settings.System.getUriFor("stream_music_volume"), false, obs
            )
        }
    }
}
