package de.bernos.wear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import de.bernos.wear.MainActivity
import de.bernos.wear.NowPlayingSummary
import de.bernos.wear.R

/** Aufbau der Kachel; getrennt vom Dienst, damit er sich testen lässt. */
object TileLayout {
    const val ID_PLAY_PAUSE = "playPause"
    const val ID_NEXT = "next"
    const val ID_PREVIOUS = "previous"
    const val ID_OPEN = "open"

    const val ICON_PLAY = "play"
    const val ICON_PAUSE = "pause"
    const val ICON_NEXT = "next"
    const val ICON_PREVIOUS = "previous"

    // Ohne ausdrückliche Farbe zeichnet protolayout-material Text in ON_PRIMARY (dunkelgrau),
    // gedacht für helle Knöpfe – auf dem schwarzen Kachelhintergrund kaum lesbar.
    private val TITLE_COLOR = argb(Colors.ON_SURFACE)
    private val SUBTITLE_COLOR = argb(0xFFBDC1C6.toInt())
    private val ROOM_COLOR = argb(Colors.PRIMARY)

    fun build(context: Context, device: DeviceParameters, summary: NowPlayingSummary): LayoutElementBuilders.LayoutElement {
        val openApp = ModifiersBuilders.Clickable.Builder()
            .setId(ID_OPEN)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()

        val room = summary.room
        if (room == null) {
            val message = if (summary.connected) R.string.tile_no_room else R.string.connecting
            return PrimaryLayout.Builder(device)
                .setContent(
                    Text.Builder(context, context.getString(message))
                        .setTypography(Typography.TYPOGRAPHY_BODY1)
                        .setColor(TITLE_COLOR)
                        .setMaxLines(3)
                        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                        .build(),
                )
                .setPrimaryChipContent(CompactChip.Builder(context, context.getString(R.string.open_app), openApp, device).build())
                .build()
        }

        val content = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Text.Builder(context, summary.title ?: context.getString(R.string.nothing_playing))
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(TITLE_COLOR)
                    .setMaxLines(2)
                    .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
                    .build(),
            )
            .apply {
                summary.subtitle?.let {
                    addContent(
                        Text.Builder(context, it)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(SUBTITLE_COLOR)
                            .setMaxLines(1)
                            .build(),
                    )
                }
            }
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(
                MultiButtonLayout.Builder()
                    .addButtonContent(button(context, ID_PREVIOUS, ICON_PREVIOUS, R.string.previous, primary = false))
                    .addButtonContent(
                        if (summary.isPlaying) {
                            button(context, ID_PLAY_PAUSE, ICON_PAUSE, R.string.pause, primary = true)
                        } else {
                            button(context, ID_PLAY_PAUSE, ICON_PLAY, R.string.play, primary = true)
                        },
                    )
                    .addButtonContent(button(context, ID_NEXT, ICON_NEXT, R.string.next, primary = false))
                    .build(),
            )
            .build()

        return PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(
                Text.Builder(context, room)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ROOM_COLOR)
                    .setMaxLines(1)
                    .build(),
            )
            .setContent(content)
            .build()
    }

    private fun button(context: Context, id: String, icon: String, description: Int, primary: Boolean): Button {
        // LoadAction: Die Kachel fordert sich neu an; der Dienst führt dann den Befehl aus.
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()
        return Button.Builder(context, clickable)
            .setIconContent(icon)
            .setContentDescription(context.getString(description))
            .setButtonColors(if (primary) ButtonDefaults.PRIMARY_COLORS else ButtonDefaults.SECONDARY_COLORS)
            .build()
    }
}
