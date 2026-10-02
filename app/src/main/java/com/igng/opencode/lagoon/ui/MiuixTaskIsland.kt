package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.TaskSummary
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Task summary reserves its own space beneath the toolbar. */
@Composable
internal fun MiuixTaskIsland(summary: TaskSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
  if (summary.isEmpty) return
  Text(summary.text.orEmpty(), modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.primary))
}
