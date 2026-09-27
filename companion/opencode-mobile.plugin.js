/** Copy to ~/.config/opencode/plugins/opencode-mobile.js on the OpenCode host. */
export const OpenCodeMobilePlugin = async ({ directory }) => {
  const endpoint = process.env.OPENCODE_MOBILE_COMPANION_URL
  const secret = process.env.OPENCODE_MOBILE_PLUGIN_SECRET
  if (!endpoint || !secret) return {}
  const allowed = new Set([
    "session.status", "session.idle", "session.error", "permission.asked", "permission.replied",
    "question.asked", "question.replied", "message.part.updated",
  ])
  return {
    event: async ({ event }) => {
      if (!allowed.has(event.type)) return
      const properties = event.properties ?? {}
      const part = properties.part ?? {}
      const sessionId = properties.sessionID ?? part.sessionID
      if (!sessionId) return
      if (event.type === "message.part.updated" && !["tool", "reasoning"].includes(part.type)) return
      const body = {
        type: event.type,
        sessionId,
        directory,
        status: properties.status?.type,
        tool: part.type === "tool" ? part.tool : undefined,
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
