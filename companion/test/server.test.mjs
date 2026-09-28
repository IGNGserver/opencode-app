import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createCompanion, mapEvent, signPushPayload, signPushPayloadV1 } from '../src/server.mjs'

test('mapEvent matches the shared task-event contract', () => {
  const contract = JSON.parse(readFileSync(fileURLToPath(new URL('../../docs/task-event-contract.json', import.meta.url)), 'utf8'))
  for (const testCase of contract.cases) {
    const previous = testCase.previous ? { phase: testCase.previous } : null
    const next = mapEvent(testCase.companion, previous)
    const phase = next?.phase ?? previous?.phase ?? 'IDLE'
    assert.equal(phase, testCase.expectedPhase, testCase.name)
  }
})

test('push signature matches the app vector and authenticates delivery', () => {
  const data = { sessionId: 'ses-1', serverId: 'srv-1', phase: 'WAITING_PERMISSION', detail: '等待权限确认', title: '构建' }
  // Legacy v1 vector cross-checked against the Android PushMessageVerifier.signV1('topsecret', ...).
  assert.equal(signPushPayloadV1('topsecret', data), 'yO0ubha7-M4U66aWoIeWbSdk7z0sVsQtcvVZhB-1Who')
  // v2 vector cross-checked against the Android PushMessageVerifier.sign('topsecret', ...).
  const v2 = { version: 2, sessionId: 'ses-1', serverId: 'srv-1', directory: '/repo', phase: 'WAITING_PERMISSION', detail: '等待权限确认', title: '构建', deviceId: 'dev-1', ts: '1700000000000' }
  assert.equal(signPushPayload('topsecret', v2), 'sVjCnL3JrQmEqgmfC6rPWQeUs9zxWu3ypfZKrxXVM-4')
  // A newline inside a field must not be able to imitate a field boundary.
  assert.notEqual(
    signPushPayload('topsecret', { ...v2, directory: '/repo\nphase', phase: 'x' }),
    signPushPayload('topsecret', { ...v2, directory: '/repo', phase: 'phase\nx' })
  )
})

test('task events become push phases without message content', () => {
  assert.deepEqual(mapEvent({ type: 'permission.asked' }), { phase: 'WAITING_PERMISSION', detail: '等待权限确认' })
  assert.deepEqual(mapEvent({ type: 'session.idle' }, { phase: 'THINKING' }), { phase: 'COMPLETED', detail: '任务已完成' })
  assert.equal(mapEvent({ type: 'session.idle' }, { phase: 'COMPLETED' }), null)
})

test('registration only succeeds once the registry is durable', async () => {
  const fs = await import('node:fs/promises')
  const dir = `/tmp/opencode-mobile-companion-durable-${randomUUID()}`
  await fs.mkdir(dir, { recursive: true })
  // Make the atomic rename target un-writable by pre-creating a directory where the registry file goes.
  await fs.mkdir(`${dir}.json`, { recursive: true })
  const companion = createCompanion({ pluginSecret: 'secret', registryFile: `${dir}.json`,
    verifyDevice: async () => true, fetchSession: async () => ({}), send: async () => {} })
  const server = createServer(companion.handle).listen(0, '127.0.0.1')
  try {
    await new Promise(resolve => server.once('listening', resolve))
    const url = `http://127.0.0.1:${server.address().port}`
    const response = await fetch(url + '/v1/devices', { method: 'POST', headers: { authorization: 'Basic valid' },
      body: JSON.stringify({ deviceId: 'device', serverKey: 'server-a', token: 'token' }) })
    // A registry that cannot be written must not report a durable registration.
    assert.notEqual(response.status, 200)
  } finally {
    server.close()
    await fs.rm(dir, { recursive: true, force: true })
    await fs.rm(`${dir}.json`, { recursive: true, force: true })
  }
})

test('failed sends are retried instead of acknowledged', async () => {
  let attempts = 0
  const companion = createCompanion({ pluginSecret: 'secret', registryFile: `/tmp/opencode-mobile-companion-outbox-${randomUUID()}.json`,
    verifyDevice: async () => true, fetchSession: async () => ({ title: 'Build task' }),
    send: async () => { attempts += 1; throw new Error('fcm unavailable') }, retryDelayMs: 5 })
  const server = createServer(companion.handle).listen(0, '127.0.0.1')
  try {
    await new Promise(resolve => server.once('listening', resolve))
    const url = `http://127.0.0.1:${server.address().port}`
    await fetch(url + '/v1/devices', { method: 'POST', headers: { authorization: 'Basic valid' },
      body: JSON.stringify({ deviceId: 'device', serverKey: 'server-a', token: 'token' }) })
    await fetch(url + '/v1/events', { method: 'POST', headers: { 'x-opencode-mobile-secret': 'secret' },
      body: JSON.stringify({ sessionId: 'session', serverKey: 'server-a', type: 'permission.asked', directory: '/project' }) })
    await new Promise(resolve => setTimeout(resolve, 200))
    // A failed delivery must be attempted more than once; the identical event must not be re-queued.
    assert.ok(attempts > 1, `expected retries, got ${attempts}`)
  } finally { server.close() }
})

test('unregister stops delivery to a removed device', async () => {
  const sent = []
  const companion = createCompanion({ pluginSecret: 'secret', registryFile: `/tmp/opencode-mobile-companion-unreg-${randomUUID()}.json`,
    verifyDevice: async () => true, fetchSession: async () => ({ title: 'Build task' }), send: async (token, data) => sent.push({ token, data }) })
  const server = createServer(companion.handle).listen(0, '127.0.0.1')
  try {
    await new Promise(resolve => server.once('listening', resolve))
    const url = `http://127.0.0.1:${server.address().port}`
    await fetch(url + '/v1/devices', { method: 'POST', headers: { authorization: 'Basic valid' },
      body: JSON.stringify({ deviceId: 'device', serverKey: 'server-a', token: 'token' }) })
    const removed = await fetch(url + '/v1/devices/unregister', { method: 'POST', headers: { authorization: 'Basic valid' },
      body: JSON.stringify({ deviceId: 'device', serverKey: 'server-a' }) })
    assert.equal(removed.status, 200)
    await fetch(url + '/v1/events', { method: 'POST', headers: { 'x-opencode-mobile-secret': 'secret' },
      body: JSON.stringify({ sessionId: 'session', serverKey: 'server-a', type: 'permission.asked' }) })
    assert.equal(sent.length, 0)
  } finally { server.close() }
})

test('registration authenticates and event delivery only targets registered device', async () => {
  const sent = []
  const companion = createCompanion({ pluginSecret: 'secret', registryFile: `/tmp/opencode-mobile-companion-test-${randomUUID()}.json`,
    verifyDevice: async auth => auth === 'Basic valid', fetchSession: async () => ({ title: 'Build task' }),
    send: async (token, data) => sent.push({ token, data }) })
  const server = createServer(companion.handle).listen(0, '127.0.0.1')
  try {
    await new Promise(resolve => server.once('listening', resolve))
    const url = `http://127.0.0.1:${server.address().port}`
    const denied = await fetch(url + '/v1/devices', { method: 'POST', body: '{}' })
    assert.equal(denied.status, 401)
    const registered = await fetch(url + '/v1/devices', { method: 'POST', headers: { authorization: 'Basic valid' },
      body: JSON.stringify({ deviceId: 'device', serverId: 'server', profileId: 'profile-a', serverKey: 'server-a', token: 'token' }) })
    assert.equal(registered.status, 200)
    const other = await fetch(url + '/v1/devices', { method: 'POST', headers: { authorization: 'Basic valid' },
      body: JSON.stringify({ deviceId: 'other', serverId: 'server', profileId: 'profile-b', serverKey: 'server-b', token: 'other-token' }) })
    assert.equal(other.status, 200)
    const ignored = await fetch(url + '/v1/events', { method: 'POST', headers: { 'x-opencode-mobile-secret': 'wrong' },
      body: JSON.stringify({ sessionId: 'session', type: 'session.idle' }) })
    assert.equal(ignored.status, 401)
    const payload = { sessionId: 'session', serverKey: 'server-a', type: 'permission.asked', directory: '/project' }
    const event = await fetch(url + '/v1/events', { method: 'POST', headers: { 'x-opencode-mobile-secret': 'secret' }, body: JSON.stringify(payload) })
    // 202 = accepted for delivery, not a claim that the device already received it.
    assert.equal(event.status, 202)
    for (let i = 0; i < 50 && sent.length === 0; i++) await new Promise(resolve => setTimeout(resolve, 10))
    assert.equal(sent.length, 1)
    assert.equal(sent[0].token, 'token')
    assert.equal(sent[0].data.serverId, 'profile-a')
    assert.equal(sent[0].data.phase, 'WAITING_PERMISSION')
    assert.equal(sent[0].data.title, 'Build task')
    // FCM data payloads must be a flat map of string values.
    assert.equal(typeof sent[0].data.version, 'string')
    assert.equal(sent[0].data.version, '2')
  } finally { server.close() }
})


test('plugin forwards metadata only', async () => {
  const oldUrl = process.env.OPENCODE_MOBILE_COMPANION_URL
  const oldSecret = process.env.OPENCODE_MOBILE_PLUGIN_SECRET
  const oldServerKey = process.env.OPENCODE_MOBILE_SERVER_KEY
  const oldFetch = globalThis.fetch
  process.env.OPENCODE_MOBILE_COMPANION_URL = 'http://127.0.0.1:4344'
  process.env.OPENCODE_MOBILE_PLUGIN_SECRET = 'fixture-secret'
  process.env.OPENCODE_MOBILE_SERVER_KEY = 'server-a'
  const sent = []
  globalThis.fetch = async (_url, options) => { sent.push(JSON.parse(options.body)); return { ok: true } }
  try {
    const { OpenCodeMobilePlugin } = await import('../opencode-mobile.plugin.js')
    const plugin = await OpenCodeMobilePlugin({ directory: '/demo' })
    await plugin.event({ event: { type: 'message.part.updated', properties: { sessionID: 'session', part: {
      type: 'tool', tool: 'bash', state: { input: { command: 'private content' }, output: 'private output' },
    } } } })
    assert.equal(sent.length, 1)
    assert.equal(sent[0].tool, 'bash')
    assert.equal(JSON.stringify(sent[0]).includes('private'), false)
    // The derived tool kind drives the notification phase on both sides.
    assert.equal(sent[0].toolKind, 'TOOL')
    await plugin.event({ event: { type: 'message.part.updated', properties: { sessionID: 'session', part: {
      type: 'tool', tool: 'bash', state: { input: { command: 'gradle test' } } },
    } } })
    assert.equal(sent[1].toolKind, 'TESTING')
    assert.equal(JSON.stringify(sent[1]).includes('gradle'), false)
  } finally {
    globalThis.fetch = oldFetch
    if (oldUrl === undefined) delete process.env.OPENCODE_MOBILE_COMPANION_URL
    else process.env.OPENCODE_MOBILE_COMPANION_URL = oldUrl
    if (oldSecret === undefined) delete process.env.OPENCODE_MOBILE_PLUGIN_SECRET
    else process.env.OPENCODE_MOBILE_PLUGIN_SECRET = oldSecret
    if (oldServerKey === undefined) delete process.env.OPENCODE_MOBILE_SERVER_KEY
    else process.env.OPENCODE_MOBILE_SERVER_KEY = oldServerKey
  }
})
