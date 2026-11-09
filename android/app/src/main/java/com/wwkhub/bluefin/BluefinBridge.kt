package com.wwkhub.bluefin

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.annotation.Keep

interface BluefinHost {
    fun isSoundOn(): Boolean
    fun onScriptMessage(message: String)
    fun onPlayCue(name: String, kind: String)
}

@Keep
class BluefinBridge(private val host: BluefinHost) {
    @JavascriptInterface
    fun soundEnabled(): Boolean = host.isSoundOn()

    @JavascriptInterface
    fun play(name: String?, kind: String?) {
        if (!host.isSoundOn()) return
        host.onPlayCue(name.orEmpty(), kind.orEmpty())
    }

    @JavascriptInterface
    fun report(message: String?) {
        host.onScriptMessage(message?.take(400).orEmpty())
    }
}

class CuePlayer {
    private var generator: ToneGenerator? = null
    private var lastAt = 0L

    fun play(name: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 90) return
        lastAt = now
        try {
            val gen = generator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 60).also { generator = it }
            val (tone, ms) = cue(name)
            gen.startTone(tone, ms)
        } catch (error: RuntimeException) {
            Log.w(TAG, "tone failed", error)
        }
    }

    fun release() {
        generator?.release()
        generator = null
    }

    private fun cue(name: String): Pair<Int, Int> = when (name) {
        "poke" -> ToneGenerator.TONE_PROP_BEEP to 70
        "purr", "happy", "love", "sparkle" -> ToneGenerator.TONE_PROP_ACK to 110
        "grab" -> ToneGenerator.TONE_PROP_BEEP2 to 50
        "land", "sad", "cry" -> ToneGenerator.TONE_PROP_NACK to 130
        "snore", "yawn" -> ToneGenerator.TONE_SUP_BUSY to 180
        "jump", "whoosh" -> ToneGenerator.TONE_PROP_BEEP to 80
        else -> ToneGenerator.TONE_PROP_BEEP to 60
    }

    private companion object {
        const val TAG = "Bluefin"
    }
}
