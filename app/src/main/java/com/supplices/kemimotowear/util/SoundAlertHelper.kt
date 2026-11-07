package com.supplices.kemimotowear.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Audio alert utility for dispatching clear, piercing acoustic alerts on the Wear OS watch speaker.
 * Specifically engineered for safety warnings (e.g. 90-minute continuous heating shutdown)
 * that must be audible even through winter gear, gloves, or motorcycle helmets.
 */
object SoundAlertHelper {

    /**
     * Plays a brief, penetrating, loud acoustic beep sequence on the ALARM stream.
     * Uses DTMF_D (high frequency combination) at 100% volume for high intelligibility.
     */
    fun playSafetyBeep(context: Context) {
        Thread {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                // First beep (250 ms)
                toneGen.startTone(ToneGenerator.TONE_DTMF_D, 250)
                Thread.sleep(350)
                // Second beep (350 ms)
                toneGen.startTone(ToneGenerator.TONE_DTMF_D, 350)
                Thread.sleep(450)
                // Release native audio track
                toneGen.release()
            } catch (_: Exception) {}
        }.start()
    }
}
