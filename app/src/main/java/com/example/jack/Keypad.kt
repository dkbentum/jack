package com.example.jack

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Jack's on-screen keypad (kiosk-friendly: no system keyboard needed).
 *
 * v3.13 look: workshop "panel" keys — light grey keys with a firm border, black labels in the
 * condensed font, a black mode key (ABC / 123), a red CLR and an icon backspace. Keys darken while
 * pressed and give a short vibration, so taps are felt as well as seen.
 *
 * @param isNumericOnly digits only, no way to switch to letters (PIN entry).
 * @param startInNumberMode letters are available via the "ABC" key, but the pad opens on digits
 *        (Attendee IDs: mostly numbers, sometimes letters).
 */
@Composable
fun CustomKeypad(
    onCharClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    isNumericOnly: Boolean = false,
    startInNumberMode: Boolean = false
) {
    var isAlphaMode by remember { mutableStateOf(!isNumericOnly && !startInNumberMode) }

    val alphaRows = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M", "⌫"),
        listOf("123", " ", "CLR")
    )

    val digitRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("CLR", "0", "⌫")
    )

    // Switchable pads get an "ABC" key so you can always get back to letters.
    val rows = when {
        isNumericOnly -> digitRows
        isAlphaMode -> alphaRows
        else -> digitRows + listOf(listOf("ABC"))
    }
    val keyHeight = if (isNumericOnly || !isAlphaMode) 56.dp else 48.dp
    val gap = if (isAlphaMode) 5.dp else 8.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                row.forEach { label ->
                    val weight = when (label) {
                        " " -> 3f
                        "123", "CLR" -> if (isAlphaMode) 1.5f else 1f
                        else -> 1f
                    }
                    KeypadKey(
                        label = label,
                        height = keyHeight,
                        modifier = Modifier.weight(weight),
                        onClick = {
                            when (label) {
                                "CLR" -> onClear()
                                "⌫" -> onBackspace()
                                "123" -> isAlphaMode = false
                                "ABC" -> isAlphaMode = true
                                else -> onCharClick(label)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** One key. Colours by role: character, mode switch (black), clear (red), backspace (icon). */
@Composable
private fun KeypadKey(
    label: String,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val isMode = label == "123" || label == "ABC"
    val isClear = label == "CLR"
    val (base, pressedColor, content) = when {
        isMode -> Triple(JackColors.Ink, JackColors.Steel, JackColors.Paper)
        isClear -> Triple(JackColors.RedTint, JackColors.Red.copy(alpha = 0.25f), JackColors.Red)
        else -> Triple(JackColors.Paper, JackColors.Line, JackColors.Ink)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (pressed) pressedColor else base,
        border = BorderStroke(1.dp, if (isClear) JackColors.Red.copy(alpha = 0.4f) else if (isMode) JackColors.Ink else JackColors.Line),
        shadowElevation = if (pressed) 0.dp else 1.dp,
        modifier = modifier
            .height(height)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = when (label) { "⌫" -> "Delete"; " " -> "Space"; "CLR" -> "Clear"; else -> label }
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
        ) {
            when (label) {
                "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = content, modifier = Modifier.size(22.dp))
                " " -> Box(Modifier.fillMaxWidth(0.5f).height(3.dp).background(JackColors.Gray))
                else -> Text(
                    text = label,
                    fontFamily = CondensedFamily,
                    fontSize = if (label.length == 1 && label[0].isDigit()) 24.sp else if (label.length == 1) 18.sp else 15.sp,
                    color = content,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
