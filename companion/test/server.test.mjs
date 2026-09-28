import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import { createCompanion, mapEvent } from '../src/server.mjs'

test('task events become push phases without message content', () => {
  assert.deepEqual(mapEvent({ type: 'permission.asked' }), { phase: 'WAITING_PERMISSION', detail: '等待权限确认' })
  assert.deepEqual(mapEvent({ type: 'session.idle' }, { phase: 'THINKING' }), { phase: 'COMPLETED', detail: '任务已完成' })
  assert.equal(mapEvent({ type: 'session.idle' }, { phase: 'COMPLETED' }), null)
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
    assert.equal(event.status, 200)
    assert.equal(sent.length, 1)
    assert.equal(sent[0].token, 'token')
    assert.equal(sent[0].data.serverId, 'profile-a')
    assert.equal(sent[0].data.phase, 'WAITING_PERMISSION')
    assert.equal(sent[0].data.title, 'Build task')
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
