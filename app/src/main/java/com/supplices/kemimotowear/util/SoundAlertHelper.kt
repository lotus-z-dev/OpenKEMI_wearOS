package com.supplices.kemimotowear.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

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
    fun playSafetyBeep(@Suppress("UNUSED_PARAMETER") context: Context) {
        Thread({
            var toneGen: ToneGenerator? = null
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                // First beep (250 ms)
                toneGen.startTone(ToneGenerator.TONE_DTMF_D, 250)
                Thread.sleep(350)
                // Second beep (350 ms)
                toneGen.startTone(ToneGenerator.TONE_DTMF_D, 350)
                Thread.sleep(450)
            } catch (_: Exception) {
                // Ignore audio hardware or interrupt errors
            } finally {
                try {
                    toneGen?.release()
                } catch (_: Exception) {}
            }
        }, "openkemi-safety-beep").start()
    }
}
