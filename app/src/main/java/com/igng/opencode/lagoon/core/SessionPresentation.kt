package com.igng.opencode.lagoon.core

/** Server identities and execution directories are separate: a worktree is still the same project. */
fun resolveSessionProject(session: Session, projects: List<Project>): Project? {
  session.projectId?.let { id -> projects.firstOrNull { it.id == id }?.let { return it } }
  val directory = normalizedDirectory(session.directory)
  return projects.filter { project ->
    val root = normalizedDirectory(project.directory)
    root.isNotEmpty() && (directory == root || directory.startsWith(if (root == "/") root else "$root/"))
  }.maxByOrNull { normalizedDirectory(it.directory).length }
}

fun normalizedDirectory(directory: String): String {
  val path = directory.trim().replace('\\', '/').trimEnd('/').ifEmpty { if (directory.startsWith('/')) "/" else "" }
  return if (Regex("^[A-Za-z]:/").containsMatchIn(path)) path.lowercase() else path
}

enum class SessionContent { UNKNOWN, EMPTY, CONTENT }
data class SessionPreview(val content: SessionContent = SessionContent.UNKNOWN, val text: String = "")

private val defaultTitle = Regex("^(New|Child) session - \\d{4}-\\d{2}-\\d{2}T.*$", RegexOption.IGNORE_CASE)
fun Session.displayTitle(preview: String = ""): String = when {
  title.isNotBlank() && title != "未命名会话" && !defaultTitle.matches(title) -> title
  preview.isNotBlank() -> preview.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(96)
  else -> "新会话 · ${id.takeLast(6)}"
}

fun List<Message>.sessionPreview(): SessionPreview {
  val visible = filter { it.isDisplayable }
  val firstPrompt = visible.firstOrNull { it.role == "user" }?.parts
    ?.firstOrNull { it.type == "text" || it.type == "user" }?.text.orEmpty()
  return SessionPreview(if (visible.isEmpty()) SessionContent.EMPTY else SessionContent.CONTENT, firstPrompt.take(300))
}

val MessagePart.isDisplayable: Boolean get() = when (type) {
  "text", "user", "system", "synthetic", "reasoning" -> text.isNotBlank()
  "tool" -> tool.isNotBlank() || input.isNotBlank() || output.isNotBlank() || error.isNotBlank()
  "file" -> path.isNotBlank()
  "patch", "diff" -> patch.isNotBlank() || files.isNotEmpty()
  "subtask" -> text.isNotBlank() || title.isNotBlank()
  else -> text.isNotBlank() || attachments.isNotEmpty()
}
val Message.isDisplayable: Boolean get() = error?.isNotBlank() == true || parts.any { it.isDisplayable }

enum class ResourceState { NOT_LOADED, LOADING, READY, EMPTY, ERROR, UNSUPPORTED, STALE }
data class ResourceStatus(val state: ResourceState = ResourceState.NOT_LOADED, val error: String? = null)
data class SessionConfiguration(val agent: String? = null, val model: ModelChoice? = null, val agentChanged: Boolean = false, val modelChanged: Boolean = false)
data class FileReference(val path: String, val mime: String = "text/plain")

/** Preserve binary/image reference semantics instead of labelling every file as plain text. */
fun referenceMime(path: String): String = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
  "png" -> "image/png"
  "jpg", "jpeg" -> "image/jpeg"
  "gif" -> "image/gif"
  "webp" -> "image/webp"
  "svg" -> "image/svg+xml"
  "pdf" -> "application/pdf"
  "mp3" -> "audio/mpeg"
  "wav" -> "audio/wav"
  "mp4" -> "video/mp4"
  "zip" -> "application/zip"
  "bin", "exe", "so", "dll" -> "application/octet-stream"
  else -> "text/plain"
}
