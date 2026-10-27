package com.supplices.kemimotowear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.supplices.kemimotowear.R
import com.supplices.kemimotowear.ui.MainActivity

/**
 * Wear OS Tile Provider for OpenKEMI.
 * Provides quick access directly from the watch tile carousel.
 */
class VestTileService : TileService() {

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val rootLayout = buildLayout(this, requestParams.deviceConfiguration)
        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(rootLayout)
                            .build()
                    )
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .build()

        return Futures.immediateFuture(tile)
    }

    private fun buildLayout(
        context: Context,
        deviceParameters: DeviceParametersBuilders.DeviceParameters
    ): LayoutElementBuilders.LayoutElement {
        val launchIntent = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build()
            )
            .build()

        val clickAction = ModifiersBuilders.Clickable.Builder()
            .setOnClick(launchIntent)
            .setId("open_app_action")
            .build()

        val title = Text.Builder(context, context.getString(R.string.tile_title))
            .setTypography(Typography.TYPOGRAPHY_TITLE2)
            .setColor(ColorBuilders.argb(0xFFFF8800.toInt()))
            .build()

        val openChip = CompactChip.Builder(
            context,
            context.getString(R.string.btn_turn_on),
            clickAction,
            deviceParameters
        ).build()

        return PrimaryLayout.Builder(deviceParameters)
            .setPrimaryLabelTextContent(title)
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        Text.Builder(context, "🔥 OpenKEMI")
                            .setTypography(Typography.TYPOGRAPHY_TITLE3)
                            .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .setPrimaryChipContent(openChip)
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
