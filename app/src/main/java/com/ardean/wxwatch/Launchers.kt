package com.ardean.wxwatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Tile: one big tap target that launches the app. No background data work. */
class WxTileService : TileService() {
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val launch = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName).setClassName(MainActivity::class.java.name).build()).build()
        val click = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(launch).build()
        val text = LayoutElementBuilders.Text.Builder().setText("wx-watch\nRadar + aircraft\nTap to open")
            .setMaxLines(3).setFontStyle(LayoutElementBuilders.FontStyle.Builder()
                .setColor(argb(0xFF3FD07F.toInt())).setSize(androidx.wear.protolayout.DimensionBuilders.sp(16f)).build()).build()
        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).build())
            .addContent(text).build()
        val tile = TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build()
        return Futures.immediateFuture(tile)
    }
    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}

/** Watch-face complication whose tap launches the app. */
class WxComplicationService : SuspendingComplicationDataSourceService() {
    private fun tap() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
    private fun build(type: ComplicationType): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_radar)
        val desc = PlainComplicationText.Builder("Open wx-watch").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder("WX").build(), desc)
                .setMonochromaticImage(MonochromaticImage.Builder(icon).build()).setTapAction(tap()).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(icon).build(), desc)
                .setTapAction(tap()).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), desc)
                .setTapAction(tap()).build()
            else -> null
        }
    }
    override suspend fun onComplicationRequest(request: ComplicationRequest) = build(request.complicationType)
    override fun getPreviewData(type: ComplicationType) = build(type)
}
