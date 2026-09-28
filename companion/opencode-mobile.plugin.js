/** Copy to ~/.config/opencode/plugins/opencode-mobile.js on the OpenCode host. */
export const OpenCodeMobilePlugin = async ({ directory }) => {
  const endpoint = process.env.OPENCODE_MOBILE_COMPANION_URL
  const secret = process.env.OPENCODE_MOBILE_PLUGIN_SECRET
  if (!endpoint || !secret) return {}
  const allowed = new Set([
    "session.status", "session.idle", "session.error", "permission.asked", "permission.replied",
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
