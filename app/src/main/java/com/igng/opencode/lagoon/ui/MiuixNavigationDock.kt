package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Opaque, in-layout navigation; labels and selected semantics remain visible on every tab. */
@Composable
internal fun MiuixNavigationDock(selectedTab: RootTab, onTabSelected: (RootTab) -> Unit, isDark: Boolean, modifier: Modifier = Modifier) {
  val icons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
  Row(modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
    RootTab.entries.forEachIndexed { index, tab ->
      val tint = if (selectedTab == tab) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
      Column(Modifier.weight(1f).heightIn(min = 64.dp).selectable(selectedTab == tab, role = Role.Tab, onClick = { onTabSelected(tab) }).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(icons[index], null, Modifier.size(23.dp), tint = tint)
        Text(tab.label, style = MiuixTheme.textStyles.footnote2.copy(color = tint))
      }
    }
  }
}
