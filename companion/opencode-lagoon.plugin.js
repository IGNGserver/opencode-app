import { readFile, mkdir, open, rename } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'
import { randomUUID, createHash } from 'node:crypto'

/** Bounded metadata-only spool. Network failures never turn a successful enqueue into a dropped event. */
export async function createEventForwarder({ file, endpoint, secret, fetchImpl = fetch, retryMs = 2000 }) {
  let state = { producerId: randomUUID(), sequence: 0, pending: [], deadLetters: [] }
  try { state = JSON.parse(await readFile(file, 'utf8')) } catch (error) { if (error.code !== 'ENOENT') throw error }
  let serial = Promise.resolve(), timer = null, draining = null, closed = false
  function transaction(change) {
    const result = serial.catch(() => {}).then(async () => {
      const next = structuredClone(state); change(next)
      await mkdir(dirname(file), { recursive: true, mode: 0o700 })
      const f = await open(file + '.tmp', 'w', 0o600)
      try { await f.chmod(0o600); await f.writeFile(JSON.stringify(next)); await f.sync() } finally { await f.close() }
      await rename(file + '.tmp', file); state = next
      const dir = await open(dirname(file), 'r')
      try { await dir.sync() } finally { await dir.close() }
    })
    serial = result.catch(() => {})
    return result
  }
  function schedule(ms) {
    if (closed || !state.pending.length) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { timer = null; drain().catch(error => console.error('opencode-lagoon: spool failure:', error.message)) }, ms)
    timer.unref?.()
  }
  async function drain() {
    if (draining) return draining
    draining = (async () => {
      while (!closed) {
        await serial
        const event = state.pending[0]
        if (!event) return
        let response
        try {
          response = await fetchImpl(new URL('/v1/events', endpoint), { method: 'POST', redirect: 'error',
            headers: { 'content-type': 'application/json', 'x-opencode-lagoon-secret': secret },
            body: JSON.stringify(event), signal: AbortSignal.timeout(8000) })
        } catch { return }
        await response.body?.cancel()
        if (response.status === 202) await transaction(next => { next.pending = next.pending.filter(x => x.id !== event.id) })
        else if ([400, 413, 422].includes(response.status)) {
          console.error('opencode-lagoon: event rejected; retained in dead letters:', response.status)
          await transaction(next => { next.pending = next.pending.filter(x => x.id !== event.id); next.deadLetters.push({ event, status: response.status }); next.deadLetters = next.deadLetters.slice(-100) })
        } else return // Includes auth/config errors; repair configuration and restart to resume.
      }
    })()
    try { await draining } finally { draining = null; schedule(retryMs) }
  }
  schedule(1)
  return {
    enqueue: async event => {
      await transaction(next => {
        if (next.pending.length >= 5000) throw new Error('push event spool is full; delivery requires attention')
        next.pending.push({ ...event, id: randomUUID(), producerId: next.producerId, sequence: ++next.sequence, observedAt: Date.now() })
      })
      schedule(1)
    },
    drain,
    close: async () => { closed = true; if (timer) clearTimeout(timer); await draining; await serial },
  }
}
const forwarders = new Map()

/** Copy to ~/.config/opencode/plugins/opencode-lagoon.js on the OpenCode host. */

const TEST_COMMAND = /test|gradle|pytest|vitest|jest/i

/**
 * Maps a tool part to the phase category the notification reducers use. The command itself is never
 * forwarded; only this derived category leaves the host. V1 parts carry `tool`, V2 parts carry `name`.
 */
export function classifyTool(part) {
  const tool = part?.tool ?? part?.name
  if (['task', 'subagent'].includes(tool)) return 'SUBAGENT'
  if (['bash', 'shell'].includes(tool) && TEST_COMMAND.test(String(part?.state?.input?.command ?? ''))) return 'TESTING'
  return 'TOOL'
}

const toolName = part => (part.type === 'tool' ? (part.tool ?? part.name) : undefined)

export const OpenCodeLagoonPlugin = async ({ directory }) => {
  const endpoint = process.env.OPENCODE_LAGOON_COMPANION_URL
  const secret = process.env.OPENCODE_LAGOON_PLUGIN_SECRET
  if (!endpoint || !secret) return {}
  const serverKey = (process.env.OPENCODE_LAGOON_SERVER_KEY || "").trim().replace(/\/+$/, "")
  if (!serverKey) return {}
  const spool = join(process.env.OPENCODE_LAGOON_PLUGIN_QUEUE_DIR || join(homedir(), '.local/state/opencode-lagoon'),
    'plugin-' + createHash('sha256').update(serverKey + '\n' + directory).digest('hex').slice(0, 16) + '.json')
  if (!forwarders.has(spool)) forwarders.set(spool, createEventForwarder({ file: spool, endpoint, secret }))
  const forwarder = await forwarders.get(spool)
  const allowed = new Set([
    "session.status", "session.idle", "session.error", "session.aborted", "permission.asked", "permission.replied",
    "question.asked", "question.replied", "question.rejected", "permission.rejected", "message.part.updated",
    "permission.v2.asked", "permission.v2.replied", "question.v2.asked", "question.v2.replied", "question.v2.rejected",
    "session.next.prompted", "session.next.prompt.admitted", "session.next.step.started", "session.next.retried",
    "session.next.tool.called", "session.next.shell.started", "session.next.step.failed",
  ])
  return {
    event: async ({ event }) => {
      if (!allowed.has(event.type)) return
      const properties = event.properties ?? event.data ?? {}
      const part = properties.part ?? (event.type === "session.next.tool.called" ? { type: "tool", name: properties.tool, state: { input: properties.input } }
        : event.type === "session.next.shell.started" ? { type: "tool", name: "shell", state: { input: { command: properties.command } } } : {})
      const sessionId = properties.sessionID ?? part.sessionID
      if (!sessionId) return
      if (event.type === "message.part.updated" && !["tool", "reasoning"].includes(part.type)) return
      const type = {
        "permission.v2.asked": "permission.asked",
        "permission.v2.replied": "permission.replied",
        "question.v2.asked": "question.asked",
        "question.v2.replied": "question.replied",
        "question.v2.rejected": "question.rejected",
      }[event.type] || event.type
      const body = {
        type,
        requestId: properties.requestID ?? properties.id,
        sessionId,
        directory,
        serverKey,
        status: properties.status?.type,
        tool: toolName(part),
        // Derived here (from the tool name and, for shell tools, the command) so both reducers agree
        // on TESTING/SUBAGENT/TOOL without the companion having to receive private command text.
        toolKind: part.type === "tool" ? classifyTool(part) : undefined,
        partType: part.type,
        toolStatus: part.state?.status,
      }
      await forwarder.enqueue(body)

    },
  }
}
