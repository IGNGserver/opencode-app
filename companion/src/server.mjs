import { createServer } from 'node:http'
import { readFile, writeFile, rename, mkdir, chmod } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'
import { timingSafeEqual } from 'node:crypto'

export function mapEvent(event, previous) {
  const { type, status, tool, partType } = event
  if (type === 'session.status') {
    if (status === 'busy') return { phase: 'THINKING', detail: '正在处理' }
    if (status === 'retry') return { phase: 'THINKING', detail: '正在重试' }
    if (status === 'idle') return previous?.phase === 'COMPLETED' ? null : { phase: 'COMPLETED', detail: '任务已完成' }
  }
  if (type === 'session.idle') return previous?.phase === 'COMPLETED' ? null : { phase: 'COMPLETED', detail: '任务已完成' }
  if (type === 'session.error') return { phase: 'FAILED', detail: '执行失败' }
  if (type === 'permission.asked') return { phase: 'WAITING_PERMISSION', detail: '等待权限确认' }
  if (type === 'question.asked') return { phase: 'WAITING_QUESTION', detail: '等待你的回答' }
  if (type === 'permission.replied' || type === 'permission.rejected' || type === 'question.replied' || type === 'question.rejected') return { phase: 'THINKING', detail: '继续执行' }
  if (type === 'message.part.updated' && partType === 'reasoning') return { phase: 'THINKING', detail: '正在思考' }
  if (type === 'message.part.updated' && partType === 'tool') return { phase: /test|gradle|pytest|vitest|jest/i.test(tool ?? '') ? 'TESTING' : tool === 'task' ? 'SUBAGENT' : 'TOOL', detail: `正在运行 ${String(tool || '工具').slice(0, 40)}` }
  return null
}

export function safeEqual(left, right) {
  const a = Buffer.from(left ?? '')
  const b = Buffer.from(right ?? '')
  return a.length === b.length && timingSafeEqual(a, b)
}

export function createCompanion({ opencodeUrl, pluginSecret, registryFile, verifyDevice, send, fetchSession, serverKey = "" }) {
  const devices = new Map()
  const states = new Map()
  let persist = Promise.resolve()
  async function load() {
    try {
      const data = JSON.parse(await readFile(registryFile, 'utf8'))
      for (const item of data.devices ?? []) devices.set(`${item.serverKey || item.serverId}:${item.deviceId}`, item)
    } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  function save() {
    persist = persist.then(async () => {
      await mkdir(dirname(registryFile), { recursive: true, mode: 0o700 })
      const temp = `${registryFile}.tmp`
      await writeFile(temp, JSON.stringify({ devices: [...devices.values()] }), { mode: 0o600 })
      await chmod(temp, 0o600)
      await rename(temp, registryFile)
    })
    return persist
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
        await save()
        return reply(res, 200, { registered: true })
      }
      if (req.method === 'POST' && path === '/v1/events') {
        if (!safeEqual(req.headers['x-opencode-mobile-secret'], pluginSecret)) return reply(res, 401, { error: 'unauthorized' })
        const event = await body(req)
        if (typeof event.sessionId !== 'string' || event.sessionId.length > 256 || !event.sessionId || typeof event.type !== 'string' || typeof event.serverKey !== 'string' || !event.serverKey) return reply(res, 400, { error: 'invalid event' })
        if (serverKey && event.serverKey !== serverKey) return reply(res, 403, { error: 'wrong server' })
        const stateKey = `${event.serverKey}:${event.sessionId}`
        const before = states.get(stateKey)
        const next = mapEvent(event, before)
        if (next && (next.phase !== before?.phase || next.detail !== before?.detail)) {
          states.set(stateKey, next)
          const session = await fetchSession(event.sessionId, event.directory).catch(() => ({}))
          const payload = {
            sessionId: event.sessionId,
            directory: String(event.directory ?? '').slice(0, 500),
            title: String(session.title ?? 'OpenCode 任务').slice(0, 80),
            phase: next.phase,
            detail: next.detail,
          }
          await Promise.allSettled([...devices.values()].filter(device => device.serverKey === event.serverKey).map(async device => {
            await send(device.token, { ...payload, serverId: device.profileId || device.serverId })
          }))
        }
        return reply(res, 200, { accepted: true })
      }
      reply(res, 404, { error: 'not found' })
    } catch (error) {
      reply(res, error?.message === 'request too large' ? 413 : 400, { error: 'invalid request' })
    }
  }
  return { load, handle, devices, states }
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
