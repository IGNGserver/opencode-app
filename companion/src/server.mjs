import { createServer } from 'node:http'
import { readFile, writeFile, rename, mkdir, chmod } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'
import { createHmac, timingSafeEqual, randomUUID } from 'node:crypto'

/** Phases that mean the agent is mid-task; only these may transition to COMPLETED on an idle event. */
const ACTIVE_PHASES = new Set(['THINKING', 'TOOL', 'SUBAGENT', 'TESTING', 'WAITING_PERMISSION', 'WAITING_QUESTION'])

export function mapEvent(event, previous) {
  const { type, status, tool, partType } = event
  if (type === 'session.status') {
    if (status === 'busy') return { phase: 'THINKING', detail: '正在处理' }
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
      : /test|gradle|pytest|vitest|jest/i.test(tool ?? '') ? 'TESTING' : tool === 'task' ? 'SUBAGENT' : 'TOOL'
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
 * Canonical signed payload (v2), byte-identical to the app's PushMessageVerifier.payloadV2().
 *
 * Length-prefixing each field removes the field-boundary ambiguity of newline joining (e.g. a `\n`
 * inside `detail` must not be able to imitate a boundary into `title`), and `directory`/`deviceId`
 * are signed so they cannot be rewritten in transit.
 */
export const SIGNED_FIELDS_V2 = ['version', 'sessionId', 'serverId', 'directory', 'phase', 'detail', 'title', 'deviceId', 'ts']
export function signPushPayload(pluginSecret, data) {
  const fields = SIGNED_FIELDS_V2.map(key => String(data[key] ?? ''))
  const canonical = '2|' + fields.map(value => `${Buffer.byteLength(value, 'utf8')}:${value}`).join('|')
  return createHmac('sha256', pluginSecret).update(canonical, 'utf8').digest('base64url')
}

/** Legacy v1 payload, still accepted during migration. */
export function signPushPayloadV1(pluginSecret, data) {
  const canonical = ['sessionId', 'serverId', 'phase', 'detail', 'title'].map(key => data[key] ?? '').join('\n')
  return createHmac('sha256', pluginSecret).update(canonical, 'utf8').digest('base64url')
}

const DEVICE_TTL_MS = 180 * 24 * 60 * 60 * 1000

export function createCompanion({ opencodeUrl, pluginSecret, registryFile, verifyDevice, send, fetchSession, serverKey = "", outboxFile = registryFile + '.outbox', retryDelayMs = 5_000 }) {
  const STATE_TTL_MS = 6 * 60 * 60 * 1000
  const MAX_STATES = 2000
  const MAX_OUTBOX = 500
  const MAX_SEND_ATTEMPTS = 6
  const devices = new Map()
  const states = new Map()
  // Durable delivery queue. A state update is only recorded as delivered once the device send has
  // been accepted; failures stay queued and are retried, so a transient FCM error cannot silently
  // drop the only completion notification (A06).
  const outbox = []
  // Per-session serial queue: concurrent events for one session are reduced and delivered in order.
  const sessionQueues = new Map()
  let persist = Promise.resolve()
  let outboxPersist = Promise.resolve()
  let timer = null

  async function load() {
    try {
      const data = JSON.parse(await readFile(registryFile, 'utf8'))
      for (const item of data.devices ?? []) devices.set(`${item.serverKey || item.serverId}:${item.deviceId}`, item)
    } catch (error) { if (error.code !== 'ENOENT') throw error }
    try {
      const data = JSON.parse(await readFile(outboxFile, 'utf8'))
      for (const item of data.pending ?? []) outbox.push(item)
    } catch (error) { if (error.code !== 'ENOENT') throw error }
    pruneDevices()
  }
  function pruneDevices(now = Date.now()) {
    for (const [key, device] of devices) if (now - (device.updated ?? 0) > DEVICE_TTL_MS) devices.delete(key)
  }
  function isExpired(device, now = Date.now()) {
    return now - (device.updated ?? 0) > DEVICE_TTL_MS
  }
  // Per-session push state is only a de-dup cache; bound it so a long-lived process (or a flood of
  // distinct session ids) cannot grow the heap without limit.
  function pruneStates(now = Date.now()) {
    for (const [key, value] of states) if (now - (value.at ?? 0) > STATE_TTL_MS) states.delete(key)
    while (states.size > MAX_STATES) states.delete(states.keys().next().value)
  }
  async function writeRegistry() {
    pruneDevices()
    await mkdir(dirname(registryFile), { recursive: true, mode: 0o700 })
    const temp = `${registryFile}.tmp`
    await writeFile(temp, JSON.stringify({ devices: [...devices.values()] }), { mode: 0o600 })
    await chmod(temp, 0o600)
    await rename(temp, registryFile)
  }
  /** Persists the device registry. A rejected promise must never be chained again; the leading catch
   *  ensures one disk failure does not permanently stop future writes. */
  function save() {
    persist = persist.catch(() => {}).then(writeRegistry).catch(error => {
      console.error('companion: failed to persist device registry:', error.message)
    })
    return persist
  }
  /** Same, but the caller must know whether the registry is durable (A08). */
  async function saveStrict() {
    persist = persist.catch(() => {}).then(writeRegistry)
    return persist
  }
  function saveOutbox() {
    outboxPersist = outboxPersist.catch(() => {}).then(async () => {
      await mkdir(dirname(outboxFile), { recursive: true, mode: 0o700 })
      const temp = `${outboxFile}.tmp`
      await writeFile(temp, JSON.stringify({ pending: outbox.slice(-MAX_OUTBOX) }), { mode: 0o600 })
      await chmod(temp, 0o600)
      await rename(temp, outboxFile)
    }).catch(error => {
      console.error('companion: failed to persist push outbox:', error.message)
    })
    return outboxPersist
  }
  function enqueue(delivery) {
    if (outbox.length >= MAX_OUTBOX) outbox.shift()
    outbox.push(delivery)
    saveOutbox()
  }
  /** Delivers one queued item, retrying with backoff; invalid tokens are dropped. */
  async function deliver(item) {
    const device = [...devices.values()].find(candidate => candidate.token === item.token)
    if (device && isExpired(device)) {
      console.error('companion: dropping push to expired device', device.deviceId)
      return true
    }
    try {
      await send(item.token, item.payload)
      return true
    } catch (error) {
      const code = error?.errorInfo?.code || error?.code
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token' || code === 'messaging/invalid-argument') {
        console.error('companion: removing invalid FCM token for a device')
        for (const [key, candidate] of devices) if (candidate.token === item.token) devices.delete(key)
        save()
        return true
      }
      item.attempts = (item.attempts ?? 0) + 1
      item.lastError = String(error?.message ?? error).slice(0, 200)
      return item.attempts >= MAX_SEND_ATTEMPTS
    }
  }
  async function drainOutbox() {
    if (drainOutbox.running) return
    drainOutbox.running = true
    try {
      for (let index = outbox.length - 1; index >= 0; index--) {
        const item = outbox[index]
        if ((item.nextAttemptAt ?? 0) > Date.now()) continue
        // eslint-disable-next-line no-await-in-loop
        const done = await deliver(item)
        if (done) outbox.splice(index, 1)
        else item.nextAttemptAt = Date.now() + Math.min(retryDelayMs * 2 ** item.attempts, 10 * 60_000)
      }
      if (outbox.length > 0) {
        timer = setTimeout(() => { timer = null; drainOutbox().catch(() => {}) }, retryDelayMs)
        if (timer.unref) timer.unref()
      }
      await saveOutbox()
    } finally {
      drainOutbox.running = false
    }
  }
  /** Serializes handling of one session so concurrent events reduce and deliver in a stable order. */
  function withSessionQueue(key, task) {
    const previous = sessionQueues.get(key) ?? Promise.resolve()
    const next = previous.catch(() => {}).then(task).catch(error => {
      console.error('companion: session delivery failed:', error.message)
    })
    sessionQueues.set(key, next)
    next.finally(() => { if (sessionQueues.get(key) === next) sessionQueues.delete(key) })
    return next
  }
  async function body(req) {
    let text = ''
    for await (const chunk of req) {
      text += chunk
      if (text.length > 16_384) throw new Error('request too large')
    }
    return JSON.parse(text || '{}')
  }
  function reply(res, status, data) {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(JSON.stringify(data))
  }
  async function handle(req, res) {
    try {
      const path = new URL(req.url, 'http://localhost').pathname
      if (req.method === 'GET' && path === '/health') return reply(res, 200, { healthy: true })
      if (req.method === 'POST' && path === '/v1/devices') {
        if (!await verifyDevice(req.headers.authorization, req.headers.cookie)) return reply(res, 401, { error: 'unauthorized' })
        const input = await body(req)
        if (![input.deviceId, input.serverKey, input.token].every(x => typeof x === 'string' && x.length > 0 && x.length < 1024)) return reply(res, 400, { error: 'invalid device' })
        if (serverKey && input.serverKey !== serverKey) return reply(res, 403, { error: 'wrong server' })
        devices.set(`${input.serverKey}:${input.deviceId}`, {
          deviceId: input.deviceId,
          serverKey: input.serverKey,
          profileId: typeof input.profileId === 'string' && input.profileId.length > 0 ? input.profileId : input.serverId,
          token: input.token,
          updated: Date.now(),
        })
        // A registration that cannot be persisted is not durable; report a retryable failure instead
        // of a false success that would vanish on restart (A08).
        try { await saveStrict() } catch (error) {
          console.error('companion: registration could not be persisted:', error.message)
          return reply(res, 503, { error: 'registry unavailable' })
        }
        return reply(res, 200, { registered: true })
      }
      if (req.method === 'POST' && path === '/v1/devices/unregister') {
        if (!await verifyDevice(req.headers.authorization, req.headers.cookie)) return reply(res, 401, { error: 'unauthorized' })
        const input = await body(req)
        if (typeof input.deviceId !== 'string' || typeof input.serverKey !== 'string') return reply(res, 400, { error: 'invalid device' })
        for (const [key, device] of devices) {
          if (device.deviceId === input.deviceId && device.serverKey === input.serverKey) devices.delete(key)
        }
        save()
        return reply(res, 200, { unregistered: true })
      }
      if (req.method === 'POST' && path === '/v1/events') {
        if (!safeEqual(req.headers['x-opencode-mobile-secret'], pluginSecret)) return reply(res, 401, { error: 'unauthorized' })
        const event = await body(req)
        if (typeof event.sessionId !== 'string' || event.sessionId.length > 256 || !event.sessionId || typeof event.type !== 'string' || typeof event.serverKey !== 'string' || !event.serverKey) return reply(res, 400, { error: 'invalid event' })
        if (serverKey && event.serverKey !== serverKey) return reply(res, 403, { error: 'wrong server' })
        const stateKey = `${event.serverKey}:${event.sessionId}`
        await withSessionQueue(stateKey, async () => {
          pruneStates()
          const before = states.get(stateKey)
          const next = mapEvent(event, before)
          if (!next || (next.phase === before?.phase && next.detail === before?.detail)) return
          // Record the accepted state before delivery so re-delivery of the same event is de-duped,
          // but only for events we are actually going to attempt (A06).
          states.set(stateKey, { ...next, at: Date.now() })
          const session = await fetchSession(event.sessionId, event.directory).catch(() => ({}))
          const deviceIds = [...devices.values()].filter(device => device.serverKey === event.serverKey)
          for (const device of deviceIds) {
            if (isExpired(device)) continue
            const payload = {
              version: 2,
              sessionId: event.sessionId,
              serverId: device.profileId || device.serverId,
              directory: String(event.directory ?? '').slice(0, 500),
              title: String(session.title ?? 'OpenCode 任务').slice(0, 80),
              phase: next.phase,
              detail: next.detail,
              deviceId: device.deviceId,
              ts: String(Date.now()),
            }
            payload.sig = signPushPayload(pluginSecret, payload)
            enqueue({ id: randomUUID(), token: device.token, payload, attempts: 0 })
          }
          if (deviceIds.length > 0) drainOutbox()
        })
        // 202: the event is accepted and will be delivered; it is not a promise of device receipt.
        return reply(res, 202, { accepted: true })
      }
      reply(res, 404, { error: 'not found' })
    } catch (error) {
      reply(res, error?.message === 'request too large' ? 413 : 400, { error: 'invalid request' })
    }
  }
  return { load, handle, devices, states, outbox, drainOutbox }
}

export async function start() {
  const opencodeUrl = process.env.OPENCODE_URL || 'http://127.0.0.1:4096'
  const pluginSecret = process.env.OPENCODE_MOBILE_PLUGIN_SECRET
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
    const legacy = await fetch(new URL('/session?limit=1', opencodeUrl), { headers, signal: AbortSignal.timeout(3000) })
    if (legacy.status !== 404) return legacy
    return fetch(new URL('/api/session?limit=1', opencodeUrl), { headers, signal: AbortSignal.timeout(3000) })
  }
  const unauthenticated = await protectedProbe({})
  if (unauthenticated.ok) throw new Error('OpenCode Basic Auth must be enabled')
  if (![401, 403].includes(unauthenticated.status)) throw new Error(`Unable to verify OpenCode Basic Auth: HTTP ${unauthenticated.status}`)
  const { initializeApp, applicationDefault } = await import('firebase-admin/app')
  const { getMessaging } = await import('firebase-admin/messaging')
  initializeApp({ credential: applicationDefault(), projectId: process.env.FIREBASE_PROJECT_ID })
  const registryFile = process.env.REGISTRY_FILE || join(homedir(), '.local/state/opencode-mobile/devices.json')
  const companion = createCompanion({
    opencodeUrl, pluginSecret, registryFile, serverKey: configuredServerKey,
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
      const legacy = await fetch(legacyEndpoint, { headers, signal: AbortSignal.timeout(3000) })
      if (legacy.ok) return legacy.json()
      const current = await fetch(new URL(`/api/session/${encodeURIComponent(id)}`, opencodeUrl), { headers, signal: AbortSignal.timeout(3000) })
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
