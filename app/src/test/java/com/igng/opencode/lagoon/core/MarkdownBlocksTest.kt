package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class MarkdownBlocksTest {
  @Test fun preservesListsTablesInlineStylesLinksAndFencedCode() {
    val blocks = MarkdownBlocks.parse("# 标题\n\n1. **重点** 和 [链接](https://example.com)\n2. `inline`\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```kotlin\nval value = 1\n```\n")
    assertEquals("标题", blocks.first().text)
    assertTrue(blocks.any { it.prefix == "1. " && it.spans.any { span -> span.bold && span.text == "重点" } })
    assertTrue(blocks.any { it.spans.any { span -> span.href == "https://example.com" } })
    assertTrue(blocks.any { it.kind == "table-head" && it.cells.size == 2 })
    assertTrue(blocks.any { it.kind == "table-row" && it.cells.first().single().text == "1" })
    assertTrue(blocks.any { it.kind == "code" && it.text == "val value = 1\n" && it.prefix == "kotlin" })
  }
  @Test fun longOutputsAreBoundedWithoutDroppingCharacters() {
    val output = "你好🙂 ".repeat(20_000)
    val chunks = MarkdownBlocks.chunks(output)
    assertEquals(output, chunks.joinToString(""))
    assertTrue(chunks.all { it.length <= MarkdownBlocks.CHUNK && !Character.isHighSurrogate(it.last()) })
    val code = MarkdownBlocks.parse("```\n$output\n```").filter { it.kind == "code" }
    assertEquals(output + "\n", code.joinToString("") { it.text })
    assertTrue(code.all { it.text.length <= MarkdownBlocks.CHUNK })
    assertEquals(1, code.count { it.copyText != null })
  }
  @Test fun inlineMarkupDoesNotLoseEscapesAndUnsafeUrlsCannotBecomeLinks() {
    val blocks = MarkdownBlocks.parse("\\*literal\\* ~~old~~ [bad](javascript:alert(1))")
    assertEquals("*literal* old bad", blocks.single().text)
    assertTrue(blocks.single().spans.any { it.strike })
    assertTrue(blocks.flatMap { it.spans }.none { it.href?.startsWith("javascript:") == true })
  }

  @Test fun pathologicalNestingFallsBackToBoundedOriginalText() {
    val input="> ".repeat(180)+"nested value"
    val blocks=MarkdownBlocks.parse(input)
    assertEquals(input,blocks.joinToString("") { it.text })
    assertTrue(blocks.all { it.text.length <= MarkdownBlocks.CHUNK })
  }

}
