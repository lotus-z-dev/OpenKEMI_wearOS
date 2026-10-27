package com.supplices.kemimotowear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.ui.MainActivity

/**
 * Hardened Wear OS Complication Provider for OpenKEMI.
 * Displays heating flame status and launches the main application on tap.
 */
class VestComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("🔥 ON").build(),
                    contentDescription = PlainComplicationText.Builder(getString(R.string.complication_desc)).build()
                )
                    .setMonochromaticImage(
                        MonochromaticImage.Builder(
                            Icon.createWithResource(this, R.mipmap.ic_launcher)
                        ).build()
                    )
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 2f,
                    min = 0f,
                    max = 3f,
                    contentDescription = PlainComplicationText.Builder(getString(R.string.complication_desc)).build()
                )
                    .setText(PlainComplicationText.Builder("N2").build())
                    .setMonochromaticImage(
                        MonochromaticImage.Builder(
                            Icon.createWithResource(this, R.mipmap.ic_launcher)
                        ).build()
                    )
                    .build()
            }
            else -> null
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("🔥").build(),
                    contentDescription = PlainComplicationText.Builder(getString(R.string.complication_desc)).build()
                )
                    .setTitle(PlainComplicationText.Builder(getString(R.string.tile_title)).build())
                    .setTapAction(pendingIntent)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 0f,
                    min = 0f,
                    max = 3f,
                    contentDescription = PlainComplicationText.Builder(getString(R.string.complication_desc)).build()
                )
                    .setText(PlainComplicationText.Builder("🔥").build())
                    .setTapAction(pendingIntent)
                    .build()
            }
            else -> {
                NoDataComplicationData()
            }
        }
    }
}
