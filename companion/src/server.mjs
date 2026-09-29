import { createServer } from 'node:http'
import { readFile, rename, mkdir, open } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'
import { createHmac, timingSafeEqual, randomUUID } from 'node:crypto'

/** Phases that mean the agent is mid-task; only these may transition to COMPLETED on an idle event. */
const ACTIVE_PHASES = new Set(['THINKING', 'TOOL', 'SUBAGENT', 'TESTING', 'WAITING_PERMISSION', 'WAITING_QUESTION'])

export function mapEvent(event, previous) {
  const { type, status, tool, partType } = event
  if (type === 'session.status') {
    if (status === 'busy' || status === 'running') return previous && ACTIVE_PHASES.has(previous.phase) && !previous.phase.startsWith('WAITING') ? { phase: previous.phase, detail: previous.detail } : { phase: 'THINKING', detail: '正在处理' }
    if (status === 'retry') return { phase: 'THINKING', detail: '正在重试' }
    if (status === 'idle') return ACTIVE_PHASES.has(previous?.phase) ? { phase: 'COMPLETED', detail: '任务已完成' } : null
  }
  if (type === 'session.idle') return ACTIVE_PHASES.has(previous?.phase) ? { phase: 'COMPLETED', detail: '任务已完成' } : null
  if (type === 'session.error') return { phase: 'FAILED', detail: '执行失败' }
  if (type === 'session.aborted') return { phase: 'ABORTED', detail: '任务已停止' }
  if (type === 'permission.asked') return { phase: 'WAITING_PERMISSION', detail: '等待权限确认' }
  if (type === 'question.asked') return { phase: 'WAITING_QUESTION', detail: '等待你的回答' }
  if (type === 'permission.replied' || type === 'permission.rejected' || type === 'question.replied' || type === 'question.rejected') return { phase: 'THINKING', detail: '继续执行' }
  if (type === 'message.part.updated' && partType === 'reasoning') return { phase: 'THINKING', detail: '正在思考' }
  if (type === 'message.part.updated' && partType === 'tool') {
    const kind = ['TESTING', 'SUBAGENT', 'TOOL'].includes(event.toolKind)
      ? event.toolKind
      : /test|gradle|pytest|vitest|jest/i.test(tool ?? '') ? 'TESTING' : ['task', 'subagent'].includes(tool) ? 'SUBAGENT' : 'TOOL'
    return { phase: kind, detail: `正在运行 ${String(tool || '工具').slice(0, 40)}` }
  }
  return null
}

export function safeEqual(left, right) {
  const a = Buffer.from(left ?? '')
  const b = Buffer.from(right ?? '')
  return a.length === b.length && timingSafeEqual(a, b)
}

/**
 * Canonical signed payload (v3), byte-identical to the app's PushMessageVerifier.payload().
 *
 * Length-prefixing each field removes the field-boundary ambiguity of newline joining (e.g. a `\n`
 * inside `detail` must not be able to imitate a boundary into `title`), and `directory`/`deviceId`
 * are signed so they cannot be rewritten in transit.
 */
export const SIGNED_FIELDS_V3 = ['version', 'sessionId', 'serverId', 'directory', 'phase', 'detail', 'title', 'deviceId', 'ts', 'sequence']
export function signPushPayload(pluginSecret, data) {
  const fields = SIGNED_FIELDS_V3.map(key => String(data[key] ?? ''))
  const canonical = '3|' + fields.map(value => `${Buffer.byteLength(value, 'utf8')}:${value}`).join('|')
  return createHmac('sha256', pluginSecret).update(canonical, 'utf8').digest('base64url')
}

/** Legacy test vector only. Production clients reject v1/v2. */
export function signPushPayloadV1(pluginSecret, data) {
  const canonical = ['sessionId', 'serverId', 'phase', 'detail', 'title'].map(key => data[key] ?? '').join('\n')
  return createHmac('sha256', pluginSecret).update(canonical, 'utf8').digest('base64url')
}

const DEVICE_TTL_MS = 180 * 24 * 60 * 60 * 1000
const MESSAGE_TTL_MS = 24 * 60 * 60 * 1000
const keyOf = (server, device) => JSON.stringify([server, device])

// One atomic file owns registration, reduction history and pending delivery. Acknowledging an event
// before this transaction commits would create a crash window between deduplication and delivery.
export function createCompanion({ pluginSecret, pushSecret, registryFile, verifyDevice, send, fetchSession = async () => ({}),
  serverKey = '', outboxFile = registryFile + '.outbox', retryDelayMs = 5_000, maxOutbox = 500 }) {
  if (!pluginSecret || !pushSecret || pushSecret === pluginSecret) throw new Error('independent plugin and push secrets are required')
  let data = { version: 3, devices: [], states: {}, pending: [], accepted: {}, sequence: 0, deadLetters: [] }
  let serial = Promise.resolve(), timer = null, draining = null, stopped = false
  async function persist(next) {
    await mkdir(dirname(registryFile), { recursive: true, mode: 0o700 })
    const temp = registryFile + '.tmp'
    const file = await open(temp, 'w', 0o600)
    try { await file.chmod(0o600); await file.writeFile(JSON.stringify(next)); await file.sync() } finally { await file.close() }
    await rename(temp, registryFile)
    try {
      const dir = await open(dirname(registryFile), 'r')
      try { await dir.sync() } finally { await dir.close() }
    } catch (error) {
      // Rename already committed the visible file. Do not let a subsequent transaction overwrite
      // it from stale memory if directory fsync fails. The request still fails and may be retried.
      data = next
      throw error
    }
  }
  function transaction(change) {
    const result = serial.catch(() => {}).then(async () => {
      const next = structuredClone(data)
      const result = await change(next)
      await persist(next)
      data = next
      return result
    })
    serial = result.catch(() => {})
    return result
  }
  function validDevice(d, item) {
    return d.updated > Date.now() - DEVICE_TTL_MS && keyOf(d.serverKey, d.deviceId) === item.deviceKey &&
      d.token === item.token && d.profileId === item.payload.serverId
  }
  function prune(next) {
    next.devices = next.devices.filter(d => d.updated > Date.now() - DEVICE_TTL_MS)
    next.pending = next.pending.filter(item => next.devices.some(d => validDevice(d, item)))
    for (const [key, state] of Object.entries(next.states)) {
      if (!ACTIVE_PHASES.has(state.phase) && state.at < Date.now() - MESSAGE_TTL_MS) delete next.states[key]
    }
    for (const [id, value] of Object.entries(next.accepted)) if ((value.at || value) < Date.now() - MESSAGE_TTL_MS) delete next.accepted[id]
  }
  function schedule(delay = null) {
    if (stopped) return
    if (timer) clearTimeout(timer)
    timer = null
    if (!data.pending.length) return
    const wait = delay ?? Math.max(1, Math.min(...data.pending.map(item => (item.nextAttemptAt || 0) - Date.now())))
    timer = setTimeout(() => { timer = null; drainOutbox().catch(error => console.error('companion: delivery transaction failed:', error.message)) }, wait)
    timer.unref?.()
  }
  function deadLetter(next, item, reason) {
    next.deadLetters.push({ id: item.id, sessionId: item.payload.sessionId, sequence: item.payload.sequence, reason, at: Date.now() })
    next.deadLetters = next.deadLetters.slice(-200)
    console.error('companion: delivery moved to dead letters:', reason)
  }
  async function drainOutbox() {
    if (draining) return draining
    draining = (async () => {
      while (!stopped) {
        await serial
        const item = data.pending.find(item => (item.nextAttemptAt || 0) <= Date.now())
        if (!item) break
        // Registration is checked immediately before send; a withdrawal also removes pending items
        // atomically. A send already in flight cannot be recalled, but is never requeued afterwards.
        let error = null
        const valid = data.devices.some(d => validDevice(d, item))
        const expired = Number(item.payload.ts) < Date.now() - MESSAGE_TTL_MS
        if (valid && !expired) {
          try { await send(item.token, item.payload) } catch (cause) { error = cause }
        }
        await transaction(next => {
          const live = next.pending.find(x => x.id === item.id)
          if (!live) return // Superseded while this send was in flight.
          const code = error?.errorInfo?.code || error?.code
          if (expired) deadLetter(next, item, 'delivery expired')
          if (code === 'messaging/invalid-argument') deadLetter(next, item, 'invalid payload')
          if (['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(code)) {
            next.devices = next.devices.filter(d => d.token !== item.token)
            next.pending = next.pending.filter(x => x.token !== item.token)
          } else if (!error || expired || !valid || code === 'messaging/invalid-argument') {
            next.pending = next.pending.filter(x => x.id !== item.id)
          } else {
            live.attempts += 1
            live.nextAttemptAt = Date.now() + Math.min(retryDelayMs * 2 ** Math.min(live.attempts, 10), 600_000)
          }
        })
      }
    })()
    try { await draining } finally { draining = null; schedule(retryDelayMs) }
  }
  async function load() {
    let old
    try { old = JSON.parse(await readFile(registryFile, 'utf8')) } catch (error) { if (error.code !== 'ENOENT') throw error }
    if (old?.version === 3) data = old
    else if (old) {
      data.devices = (old.devices || []).map(d => ({ ...d, profileId: d.profileId || d.serverId, serverKey: d.serverKey || d.serverId }))
      // Upgrade the old queue without discarding user notifications. Keep the newest state per
      // device/session, re-sign under v3, and persist it before starting delivery.
      let legacy = []
      try { legacy = JSON.parse(await readFile(outboxFile, 'utf8')).pending || [] } catch (error) { if (error.code !== 'ENOENT') throw error }
      for (const item of legacy.sort((a,b) => Number(a.payload.ts) - Number(b.payload.ts))) {
        const d = data.devices.find(d => d.token === item.token)
        if (!d) continue
        const stateKey = keyOf(d.serverKey, item.payload.sessionId)
        const deviceKey = keyOf(d.serverKey, d.deviceId)
        const sequence = ++data.sequence
        const payload = { ...item.payload, version: '3', sequence: String(sequence), deviceId: d.deviceId, serverId: d.profileId }
        payload.sig = signPushPayload(pushSecret, payload)
        data.pending = data.pending.filter(x => !(x.deviceKey === deviceKey && x.payload.sessionId === payload.sessionId))
        data.pending.push({ id: randomUUID(), deviceKey, token: d.token, payload, attempts: 0 })
        data.states[stateKey] = { phase: payload.phase, detail: payload.detail, at: Number(payload.ts) }
      }
    }
    await transaction(prune)
    schedule(1)
  }
  async function body(req) {
    let size = 0; const chunks = []
    for await (const chunk of req) { size += chunk.length; if (size > 16_384) throw Object.assign(new Error('request too large'), { status: 413 }); chunks.push(chunk) }
    try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') } catch { throw Object.assign(new Error('invalid JSON'), { status: 400 }) }
  }
  function reply(res, status, value) {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(JSON.stringify(value))
  }
  const nonempty = (value, max = 1024) => typeof value === 'string' && value.length > 0 && value.length <= max
  async function handle(req, res) {
    try {
      const path = new URL(req.url, 'http://localhost').pathname
      if (req.method === 'GET' && path === '/health') return reply(res, 200, { healthy: true, pending: data.pending.length, deadLetters: data.deadLetters.length })
      if (req.method === 'POST' && ['/v1/devices', '/v1/devices/unregister'].includes(path)) {
        if (!await verifyDevice(req.headers.authorization, req.headers.cookie)) return reply(res, 401, { error: 'unauthorized' })
        const input = await body(req)
        if (!nonempty(input.deviceId) || !nonempty(input.serverKey)) return reply(res, 400, { error: 'invalid device' })
        if (serverKey && input.serverKey !== serverKey) return reply(res, 403, { error: 'wrong server' })
        const deviceKey = keyOf(input.serverKey, input.deviceId)
        const remove = path.endsWith('/unregister')
        const profileId = input.profileId || input.serverId
        if (!remove && (!nonempty(input.token) || !nonempty(profileId))) return reply(res, 400, { error: 'invalid registration' })
        await transaction(next => {
          prune(next)
          const old = next.devices.find(d => keyOf(d.serverKey, d.deviceId) === deviceKey)
          next.devices = next.devices.filter(d => keyOf(d.serverKey, d.deviceId) !== deviceKey)
          if (remove || old?.token !== input.token || old?.profileId !== profileId) next.pending = next.pending.filter(item => item.deviceKey !== deviceKey)
          if (!remove) next.devices.push({ deviceId: input.deviceId, serverKey: input.serverKey, profileId, token: input.token, updated: Date.now() })
        })
        schedule()
        return reply(res, 200, remove ? { unregistered: true } : { registered: true })
      }
      if (req.method === 'POST' && path === '/v1/events') {
        if (!safeEqual(req.headers['x-opencode-mobile-secret'], pluginSecret)) return reply(res, 401, { error: 'unauthorized' })
        const event = await body(req)
        if (event.producerId != null && (!nonempty(event.producerId, 256) || !Number.isSafeInteger(event.sequence) || event.sequence <= 0)) return reply(res, 400, { error: 'invalid producer sequence' })
        if (!nonempty(event.sessionId, 256) || !nonempty(event.type, 128) || !nonempty(event.serverKey) ||
          event.id != null && !nonempty(event.id, 256)) return reply(res, 400, { error: 'invalid event' })
        if (serverKey && event.serverKey !== serverKey) return reply(res, 403, { error: 'wrong server' })
        if (event.observedAt != null && (!Number.isSafeInteger(event.observedAt) || event.observedAt < Date.now() - MESSAGE_TTL_MS || event.observedAt > Date.now() + 60_000)) return reply(res, 422, { error: 'event expired or invalid observation time' })
        await transaction(async next => {
          prune(next)
          const eventKey = event.producerId ? keyOf(event.serverKey, 'producer:' + event.producerId) : event.id ? keyOf(event.serverKey, event.id) : null
          const accepted = eventKey && next.accepted[eventKey]
          if (accepted && (!event.producerId || accepted.sequence >= event.sequence)) return
          const stateKey = keyOf(event.serverKey, event.sessionId), before = next.states[stateKey]
          const permissions = new Set(before?.permissions || []), questions = new Set(before?.questions || [])
          if (event.requestId) {
            if (event.type === 'permission.asked') permissions.add(event.requestId)
            if (['permission.replied', 'permission.rejected'].includes(event.type)) permissions.delete(event.requestId)
            if (event.type === 'question.asked') questions.add(event.requestId)
            if (['question.replied', 'question.rejected'].includes(event.type)) questions.delete(event.requestId)
          }
          let after = mapEvent(event, before)
          if (after && !['FAILED', 'ABORTED'].includes(after.phase)) {
            if (permissions.size) after = { phase: 'WAITING_PERMISSION', detail: '等待权限确认' }
            else if (questions.size) after = { phase: 'WAITING_QUESTION', detail: '等待你的回答' }
          }
          if (eventKey) next.accepted[eventKey] = event.producerId ? { sequence: event.sequence, at: Date.now() } : Date.now()
          if (Object.keys(next.accepted).length > 10_000) throw new Error('event deduplication capacity reached')
          if (!after) return
          if (['FAILED', 'ABORTED'].includes(after.phase)) { permissions.clear(); questions.clear() }
          next.states[stateKey] = { ...after, permissions: [...permissions], questions: [...questions], at: Date.now() }
          if (Object.keys(next.states).length > 2000) throw new Error('active state capacity reached')
          if (after.phase === before?.phase && after.detail === before?.detail && !event.type.endsWith('.asked')) return
          const sequence = ++next.sequence
          if (!Number.isSafeInteger(sequence)) throw new Error('sequence exhausted')
          const session = await fetchSession(event.sessionId, event.directory).catch(() => ({}))
          for (const d of next.devices.filter(d => d.serverKey === event.serverKey)) {
            const deviceKey = keyOf(d.serverKey, d.deviceId)
            const payload = { version: '3', sessionId: event.sessionId, serverId: d.profileId, directory: String(event.directory || '').slice(0, 500),
              title: String(session.title || 'OpenCode 任务').slice(0, 80), phase: after.phase, detail: after.detail,
              deviceId: d.deviceId, ts: String(Date.now()), sequence: String(sequence) }
            payload.sig = signPushPayload(pushSecret, payload)
            // A newer authoritative state supersedes only this device/session's older pending state.
            next.pending = next.pending.filter(x => !(x.deviceKey === deviceKey && x.payload.sessionId === event.sessionId))
            if (next.pending.length >= maxOutbox) throw new Error('outbox capacity reached')
            next.pending.push({ id: randomUUID(), deviceKey, token: d.token, payload, attempts: 0 })
          }
        })
        schedule(1)
        return reply(res, 202, { accepted: true })
      }
      reply(res, 404, { error: 'not found' })
    } catch (error) {
      console.error('companion: request not committed:', error.message)
      reply(res, error.status || 503, { error: error.status ? 'invalid request' : 'durable state unavailable; retry' })
    }
  }
  return { load, handle, drainOutbox, close: async () => { stopped = true; if (timer) clearTimeout(timer); await draining; await serial },
    get devices() { return new Map(data.devices.map(d => [keyOf(d.serverKey, d.deviceId), d])) },
    get states() { return new Map(Object.entries(data.states)) }, get outbox() { return structuredClone(data.pending) } }
}

export async function start() {
  const opencodeUrl = process.env.OPENCODE_URL || 'http://127.0.0.1:4096'
  const pluginSecret = process.env.OPENCODE_MOBILE_PLUGIN_SECRET
  const pushSecret = process.env.OPENCODE_MOBILE_PUSH_SECRET
  const opencodePassword = process.env.OPENCODE_SERVER_PASSWORD
  const opencodeUsername = process.env.OPENCODE_SERVER_USERNAME || 'opencode'
  if (!pluginSecret || !opencodePassword) throw new Error('OPENCODE_MOBILE_PLUGIN_SECRET and OPENCODE_SERVER_PASSWORD are required')
  const credentials = 'Basic ' + Buffer.from(`${opencodeUsername}:${opencodePassword}`).toString('base64')
  const url = new URL(opencodeUrl)
  if (!['127.0.0.1', 'localhost', '::1'].includes(url.hostname)) throw new Error('OPENCODE_URL must target local OpenCode server')
  const rawServerKey = process.env.OPENCODE_MOBILE_SERVER_KEY?.trim()
  if (!rawServerKey) throw new Error('OPENCODE_MOBILE_SERVER_KEY is required and must equal the App server URL')
  const configuredServerKey = rawServerKey.replace(/\/+$/, '')
  const protectedProbe = async headers => {
    const legacy = await fetch(new URL('/session?limit=1', opencodeUrl), { headers, signal: AbortSignal.timeout(3000), redirect: 'error' })
    if (legacy.status !== 404) return legacy
    return fetch(new URL('/api/session?limit=1', opencodeUrl), { headers, signal: AbortSignal.timeout(3000), redirect: 'error' })
  }
  const unauthenticated = await protectedProbe({})
  if (unauthenticated.ok) throw new Error('OpenCode Basic Auth must be enabled')
  if (![401, 403].includes(unauthenticated.status)) throw new Error(`Unable to verify OpenCode Basic Auth: HTTP ${unauthenticated.status}`)
  const { initializeApp, applicationDefault } = await import('firebase-admin/app')
  const { getMessaging } = await import('firebase-admin/messaging')
  initializeApp({ credential: applicationDefault(), projectId: process.env.FIREBASE_PROJECT_ID })
  const registryFile = process.env.REGISTRY_FILE || join(homedir(), '.local/state/opencode-mobile/devices.json')
  const companion = createCompanion({
    opencodeUrl, pluginSecret, pushSecret, registryFile, serverKey: configuredServerKey,
    verifyDevice: async (auth, cookie) => {
      if (typeof auth !== 'string' && typeof cookie !== 'string') return false
      const headers = {}
      if (typeof auth === 'string' && auth.startsWith('Basic ')) headers.authorization = auth
      if (typeof cookie === 'string' && cookie) headers.cookie = cookie
      if (!headers.authorization && !headers.cookie) return false
      const result = await protectedProbe(headers)
      return result.ok
    },
    fetchSession: async (id, directory) => {
      const headers = { authorization: credentials }
      const legacyEndpoint = new URL(`/session/${encodeURIComponent(id)}`, opencodeUrl)
      if (directory) legacyEndpoint.searchParams.set('directory', directory)
      const legacy = await fetch(legacyEndpoint, { headers, signal: AbortSignal.timeout(3000), redirect: 'error' })
      if (legacy.ok) return legacy.json()
      const current = await fetch(new URL(`/api/session/${encodeURIComponent(id)}`, opencodeUrl), { headers, signal: AbortSignal.timeout(3000), redirect: 'error' })
      if (!current.ok) return {}
      const result = await current.json()
      return result.data ?? result
    },
    send: (token, data) => getMessaging().send({ token, data, android: { priority: data.phase.startsWith('WAITING') || data.phase === 'FAILED' ? 'high' : 'normal' } }),
  })
  await companion.load()
  const host = process.env.HOST || '127.0.0.1'
  const port = Number(process.env.PORT || 4344)
  createServer(companion.handle).listen(port, host)
}
if (process.argv[1] && new URL(import.meta.url).pathname === process.argv[1]) start().catch(error => { console.error(error.message); process.exitCode = 1 })
