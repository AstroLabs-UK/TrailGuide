package com.trailguide.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Handles spoken directions with a western / Arthur-style impression.
 * Uses the device's offline-capable Text-to-Speech engine.
 * Pitch and rate are tuned for a deeper, slower delivery.
 * Queue mutations are posted to the main thread for safety.
 */
class ArthurVoice(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private var westernMode = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var isSpeaking = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(0.78f)
            tts?.setSpeechRate(0.88f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        processQueue()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        processQueue()
                    }
                }
            })

            isReady = true
            Log.d(TAG, "TTS ready")
            processQueue()
        } else {
            Log.e(TAG, "TTS init failed")
        }
    }

    fun setWesternMode(enabled: Boolean) {
        westernMode = enabled
    }

    fun speak(instruction: String) {
        val text = if (westernMode) toWestern(instruction) else instruction
        mainHandler.post {
            queue.addLast(text)
            processQueue()
        }
    }

    fun speakNow(instruction: String) {
        val text = if (westernMode) toWestern(instruction) else instruction
        mainHandler.post {
            queue.clear()
            tts?.stop()
            isSpeaking = false
            queue.addLast(text)
            processQueue()
        }
    }

    fun stop() {
        mainHandler.post {
            queue.clear()
            tts?.stop()
            isSpeaking = false
        }
    }

    fun shutdown() {
        mainHandler.post {
            queue.clear()
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
        }
    }

    private fun processQueue() {
        if (!isReady || isSpeaking || queue.isEmpty()) return

        val text = queue.removeFirst()
        isSpeaking = true
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun toWestern(raw: String): String {
        val lower = raw.lowercase()

        return when {
            lower.contains("turn left") -> "Take the left trail, partner."
            lower.contains("turn right") -> "Hang a right."
            lower.contains("keep left") -> "Stay on the left side of the trail."
            lower.contains("keep right") -> "Stay on the right side of the trail."
            lower.contains("continue") || lower.contains("straight") -> "Keep riding straight."
            lower.contains("u-turn") || lower.contains("make a u") -> "Turn yourself around."
            lower.contains("arrive") || lower.contains("destination") -> "You've made it. Rest easy."
            lower.contains("roundabout") -> "Take the circle ahead."
            lower.contains("exit") -> "Take the exit."
            lower.contains("merge") -> "Merge onto the trail ahead."
            lower.startsWith("in ") -> {
                raw.replace("turn left", "take the left trail")
                    .replace("turn right", "hang a right")
                    .replace("Continue", "Keep riding")
            }
            else -> raw
        }
    }

    companion object {
        private const val TAG = "ArthurVoice"
    }
}
