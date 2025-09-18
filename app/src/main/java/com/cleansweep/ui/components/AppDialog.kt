package com.cleansweep.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.LocalAppTheme

@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    showDontAskAgain: Boolean = false,
    dontAskAgainChecked: Boolean = false,
    onDontAskAgainChanged: (Boolean) -> Unit = {},
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        val currentTheme = LocalAppTheme.current
        val cardContainerColor = if (currentTheme == AppTheme.AMOLED) {
            MaterialTheme.colorScheme.surface
        } else {
            CardDefaults.cardColors().containerColor
        }

        val cardModifier = if (currentTheme == AppTheme.AMOLED) {
            Modifier.border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                MaterialTheme.shapes.extraLarge
            )
        } else {
            Modifier
        }

        Card(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = cardModifier,
            colors = CardDefaults.cardColors(
                containerColor = cardContainerColor
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                title?.let {
                    it()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                text?.let {
                    it()
                    Spacer(modifier = Modifier.height(24.dp))
                }

                AnimatedVisibility(visible = showDontAskAgain) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDontAskAgainChanged(!dontAskAgainChecked) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Checkbox(
                                checked = dontAskAgainChecked,
                                onCheckedChange = null,
                                modifier = Modifier
                                    .offset(x = (4).dp)
                            )
                            Text(
                                text = "Don't ask again",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    buttons()
                }
            }
        }
    }
}