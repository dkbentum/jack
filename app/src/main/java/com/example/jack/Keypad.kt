package com.example.jack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Jack's on-screen keypad (kiosk-friendly: no system keyboard needed).
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { label ->
                    val weight = if (label == " ") 2f else 1f
                    Button(
                        onClick = {
                            when (label) {
                                "CLR" -> onClear()
                                "⌫" -> onBackspace()
                                "123" -> isAlphaMode = false
                                "ABC" -> isAlphaMode = true
                                else -> onCharClick(label)
                            }
                        },
                        modifier = Modifier
                            .weight(weight)
                            .height(if (isNumericOnly || !isAlphaMode) 48.dp else 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (label) {
                                "CLR", "⌫" -> MaterialTheme.colorScheme.errorContainer
                                "123", "ABC" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = when (label) {
                                "CLR", "⌫" -> MaterialTheme.colorScheme.onErrorContainer
                                "123", "ABC" -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (label == " ") "Space" else label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
