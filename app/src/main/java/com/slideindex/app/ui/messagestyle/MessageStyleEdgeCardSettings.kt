@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui.messagestyle

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.message.MessagePlacementFractions
import com.slideindex.app.message.MessageSettings
import com.slideindex.app.message.SideBubbleHorizontalEdge
import com.slideindex.app.ui.SettingRadioRow
import com.slideindex.app.ui.SettingsSliderRow
import com.slideindex.app.ui.miuix.CardItem
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SETTINGS_SLIDER_PERCENT_KEY_POINTS_01
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazyHint
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle

@Composable
fun edgeCardSettingsCardItems(
    settings: MessageSettings,
    enabled: Boolean,
    onOpacityChange: (Float) -> Unit,
    onAutoCollapseSecondsChange: (Int) -> Unit,
    onMaxCountChange: (Int) -> Unit = {},
    onMaxLinesChange: (Int) -> Unit = {},
    onPreviewChange: (MessageSettings) -> Unit = {},
    onPreviewCommit: () -> Unit = {},
): List<CardItem> = buildList {
    add(
        settingsCardScopeItem("edge-card-max-count") {
            SettingsSliderRow(
                title = stringResource(R.string.message_style_edge_card_max_count),
                value = settings.edgeCardMaxCount.toFloat(),
                valueRange = 1f..5f,
                steps = 3,
                enabled = enabled,
                label = settings.edgeCardMaxCount.toString(),
                formatLabel = { it.toInt().toString() },
                onValueChange = { value -> onMaxCountChange(value.toInt()) },
            )
        },
    )
    add(
        settingsCardScopeItem("edge-card-max-lines") {
            SettingsSliderRow(
                title = stringResource(R.string.message_style_edge_card_max_lines),
                value = settings.edgeCardMaxLines.coerceIn(1, 3).toFloat(),
                valueRange = 1f..3f,
                steps = 1,
                enabled = enabled,
                label = settings.edgeCardMaxLines.coerceIn(1, 3).toString(),
                formatLabel = { it.toInt().toString() },
                onValueChange = { value -> onMaxLinesChange(value.toInt()) },
            )
        },
    )
    add(
        settingsCardScopeItem("edge-card-opacity") {
            SettingsSliderRow(
                title = stringResource(R.string.message_style_edge_card_opacity),
                value = settings.edgeCardOpacity,
                valueRange = 0.1f..1f,
                enabled = enabled,
                label = "${(settings.edgeCardOpacity * 100).toInt()}%",
                formatLabel = { "${(it * 100).toInt()}%" },
                keyPoints = SETTINGS_SLIDER_PERCENT_KEY_POINTS_01,
                triggersLayoutPreview = true,
                onLayoutPreviewValueChange = { opacity ->
                    onPreviewChange(settings.copy(edgeCardOpacity = opacity))
                },
                onValueChange = { opacity ->
                    onPreviewCommit()
                    onOpacityChange(opacity)
                },
            )
        },
    )
    add(
        settingsCardScopeItem("edge-card-collapse") {
            val offLabel = stringResource(R.string.message_style_edge_card_auto_collapse_off)
            val seconds = (settings.edgeCardAutoCollapseMs / 1000).coerceIn(0, 10)
            SettingsSliderRow(
                title = stringResource(R.string.message_style_edge_card_auto_collapse),
                value = seconds.toFloat(),
                valueRange = 0f..10f,
                steps = 9,
                enabled = enabled,
                label = if (seconds <= 0) offLabel else "$seconds s",
                formatLabel = { value ->
                    val s = value.toInt()
                    if (s <= 0) offLabel else "$s s"
                },
                onValueChange = { value -> onAutoCollapseSecondsChange(value.toInt() * 1000) },
            )
        },
    )
}

fun LazyListScope.edgeCardSettingsSection(
    items: List<CardItem>,
    sectionTitle: String,
) {
    settingsLazySmallTitle(key = "message-edge-card-settings", title = sectionTitle)
    groupedCardItems(
        keyPrefix = "message-edge-card-settings",
        items = items,
    )
}

fun LazyListScope.edgeCardPlacementSection(
    settings: MessageSettings,
    enabled: Boolean,
    sectionTitle: String,
    positionHint: String,
    onHorizontalEdgeChange: (SideBubbleHorizontalEdge) -> Unit,
    onYFractionPreviewChange: (Float) -> Unit = {},
    onYFractionPreviewCommit: () -> Unit = {},
    onYFractionChange: (Float) -> Unit,
) {
    settingsLazySmallTitle(key = "message-edge-card-position", title = sectionTitle)
    settingsLazyHint(key = "message-edge-card-position-hint", text = positionHint)
    groupedCardItems(
        keyPrefix = "message-edge-card-placement",
        selectableGroup = true,
        items = buildList {
            add(
                settingsCardScopeItem("edge-card-left") {
                    SettingRadioRow(
                        title = stringResource(R.string.message_style_side_edge_left),
                        selected = settings.edgeCardHorizontalEdge == SideBubbleHorizontalEdge.Left,
                        enabled = enabled,
                        onClick = { onHorizontalEdgeChange(SideBubbleHorizontalEdge.Left) },
                    )
                },
            )
            add(
                settingsCardScopeItem("edge-card-right") {
                    SettingRadioRow(
                        title = stringResource(R.string.message_style_side_edge_right),
                        selected = settings.edgeCardHorizontalEdge == SideBubbleHorizontalEdge.Right,
                        enabled = enabled,
                        onClick = { onHorizontalEdgeChange(SideBubbleHorizontalEdge.Right) },
                    )
                },
            )
            add(
                settingsCardScopeItem("edge-card-position-y") {
                    SettingsSliderRow(
                        title = stringResource(R.string.message_style_position_y),
                        value = settings.edgeCardYFraction,
                        valueRange = MessagePlacementFractions.MIN_Y..MessagePlacementFractions.MAX_Y,
                        enabled = enabled,
                        label = "",
                        formatLabel = { "${(it * 100).toInt()}%" },
                        keyPoints = SETTINGS_SLIDER_PERCENT_KEY_POINTS_01,
                        triggersLayoutPreview = true,
                        onLayoutPreviewValueChange = onYFractionPreviewChange,
                        onValueChange = { fraction ->
                            onYFractionPreviewCommit()
                            onYFractionChange(fraction)
                        },
                    )
                },
            )
        },
    )
}
