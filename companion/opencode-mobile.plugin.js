/** Copy to ~/.config/opencode/plugins/opencode-mobile.js on the OpenCode host. */

const TEST_COMMAND = /test|gradle|pytest|vitest|jest/i

/**
 * Maps a tool part to the phase category the notification reducers use. The command itself is never
 * forwarded; only this derived category leaves the host.
 */
export function classifyTool(part) {
  if (part?.tool === 'task') return 'SUBAGENT'
  if (['bash', 'shell'].includes(part?.tool) && TEST_COMMAND.test(String(part?.state?.input?.command ?? ''))) return 'TESTING'
  return 'TOOL'
}

export const OpenCodeMobilePlugin = async ({ directory }) => {
  const endpoint = process.env.OPENCODE_MOBILE_COMPANION_URL
  const secret = process.env.OPENCODE_MOBILE_PLUGIN_SECRET
  if (!endpoint || !secret) return {}
  const allowed = new Set([
    "session.status", "session.idle", "session.error", "session.aborted", "permission.asked", "permission.replied",
    "question.asked", "question.replied", "question.rejected", "permission.rejected", "message.part.updated",
    "permission.v2.asked", "permission.v2.replied", "question.v2.asked", "question.v2.replied", "question.v2.rejected",
  ])
  return {
    event: async ({ event }) => {
      if (!allowed.has(event.type)) return
      const properties = event.properties ?? event.data ?? {}
      const part = properties.part ?? {}
      const sessionId = properties.sessionID ?? part.sessionID
      if (!sessionId) return
      if (event.type === "message.part.updated" && !["tool", "reasoning"].includes(part.type)) return
      const serverKey = (process.env.OPENCODE_MOBILE_SERVER_KEY || "").trim().replace(/\/+$/, "")
      if (!serverKey) return
      const type = {
        "permission.v2.asked": "permission.asked",
        "permission.v2.replied": "permission.replied",
        "question.v2.asked": "question.asked",
        "question.v2.replied": "question.replied",
        "question.v2.rejected": "question.rejected",
      }[event.type] || event.type
      const body = {
        type,
        sessionId,
        directory,
        serverKey,
        status: properties.status?.type,
        tool: part.type === "tool" ? part.tool : undefined,
        // Derived here (from the tool name and, for shell tools, the command) so both reducers agree
        // on TESTING/SUBAGENT/TOOL without the companion having to receive private command text.
        toolKind: part.type === "tool" ? classifyTool(part) : undefined,
        partType: part.type,
      }
      try {
        await fetch(new URL("/v1/events", endpoint), {
          method: "POST",
          headers: { "content-type": "application/json", "x-opencode-mobile-secret": secret },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(2500),
        })
      } catch { /* Push transport must not block OpenCode execution. */ }
    },
  }
}
