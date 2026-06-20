package io.furryr.file.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Embedded confirmation UI composable with inline approve/deny buttons.
 *
 * Displays a card containing the action description, a monospace detail of the
 * requested action, and two Material3-styled buttons for the user to allow or
 * deny the operation.
 *
 * @param block     The [ConfirmBlock] holding the action and description to display.
 * @param onConfirm Called when the user taps the allow button.
 * @param onDeny    Called when the user taps the deny button.
 * @param modifier  Standard Compose modifier applied to the card.
 */
@Composable
fun ConfirmBlockView(
    block: ConfirmBlock,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Title
            Text(
                text = "Confirm Action",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Description — secondary color for hierarchy
            Text(
                text = block.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action detail — monospace for code/command feel
            Text(
                text = block.action,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Buttons row — right-aligned; deny (outlined) first, allow (filled) last
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDeny) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Deny"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Deny")
                }

                Spacer(modifier = Modifier.width(12.dp))

                FilledTonalButton(onClick = onConfirm) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Allow"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow")
                }
            }
        }
    }
}
