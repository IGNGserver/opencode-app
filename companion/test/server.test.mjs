import test from 'node:test'
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {mkdtemp, mkdir, readFile, writeFile, rm} from 'node:fs/promises'
import {readFileSync} from 'node:fs'
import {createCompanion, mapEvent, signPushPayload, signPushPayloadV1} from '../src/server.mjs'
import {createEventForwarder, classifyTool, OpenCodeLagoonPlugin} from '../opencode-lagoon.plugin.js'
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function until(f) { for (let i=0;i<300;i++) { if (await f()) return; await sleep(5) } throw Error('timed out') }
async function fixture(t, send = async () => {}, extra = {}) {
  const dir = await mkdtemp('/tmp/opencode-companion-test-')
  const options = {pluginSecret:'inbound-fixture',pushSecret:'push-fixture',registryFile:dir+'/state.json',verifyDevice:async a=>a==='Basic fixture',send,fetchSession:async()=>({title:'Task'}),retryDelayMs:10,...extra}
  const app = createCompanion(options);await app.load()
  const server=createServer(app.handle).listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r))
  const post = (path, data, auth='Basic fixture')=>fetch(`http://127.0.0.1:${server.address().port}${path}`, {method:'POST',headers:{authorization:auth,'x-opencode-lagoon-secret':'inbound-fixture'},body:JSON.stringify(data)})
  const register = (id='device')=>post('/v1/devices',{deviceId:id,serverKey:'srv',profileId:'profile',token:'token-'+id})
  const event=(type,props={})=>post('/v1/events',{type,serverKey:'srv',sessionId:'ses',directory:'/repo',...props})
  const close=async()=>{await app.close();await new Promise(r=>server.close(r))}
  t.after(async()=>{await close();await rm(dir,{recursive:true,force:true})})
  return {dir,options,app,post,register,event,close}
}
test('shared task contract and raw tool categories remain equivalent',()=>{
  const fixture=JSON.parse(readFileSync(new URL('../../docs/task-event-contract.json',import.meta.url),'utf8'))
  for(const c of fixture.cases){const prev=c.previous?{phase:c.previous}:null;assert.equal(mapEvent(c.companion,prev)?.phase??prev?.phase??'IDLE',c.expectedPhase,c.name)}
  assert.equal(classifyTool({type:'tool',name:'subagent'}),'SUBAGENT')
  assert.equal(classifyTool({type:'tool',tool:'bash',state:{input:{command:'gradle test'}}}),'TESTING')
  assert.equal(mapEvent({type:'session.status',status:'running'},{phase:'TESTING',detail:'tests'}).phase,'TESTING')
})
test('v3 signature matches Android and binds sequence/device/field boundaries',()=>{
  const data={version:'3',sessionId:'ses-1',serverId:'srv-1',directory:'/repo',phase:'WAITING_PERMISSION',detail:'等待权限确认',title:'构建',deviceId:'dev-1',ts:'1700000000000',sequence:'7'}
  assert.equal(signPushPayload('topsecret',data),'xR2aa8hjbYV9Vd6uIDVIt_ZJDxiwP0okKYJQ3fDMiQU')
  for(const key of Object.keys(data))assert.notEqual(signPushPayload('topsecret',data),signPushPayload('topsecret',{...data,[key]:data[key]+'x'}))
  assert.notEqual(signPushPayload('key',{...data,detail:'a\nb',title:'c'}),signPushPayload('key',{...data,detail:'a',title:'b\nc'}))
  assert.equal(signPushPayloadV1('topsecret',{sessionId:'ses-1',serverId:'srv-1',phase:'WAITING_PERMISSION',detail:'等待权限确认',title:'构建'}),'yO0ubha7-M4U66aWoIeWbSdk7z0sVsQtcvVZhB-1Who')
})
test('authenticated registration routes string-only signed pushes',async t=>{
 const sent=[];const f=await fixture(t,async(token,p)=>sent.push({token,p}))
 assert.equal((await f.register()).status,200)
 assert.equal((await f.post('/v1/devices',{deviceId:'bad',serverKey:'srv',token:'bad'},'wrong')).status,401)
 assert.equal((await f.event('permission.asked')).status,202);await until(()=>sent.length===1)
 assert.equal(sent[0].p.serverId,'profile');assert.equal(sent[0].p.version,'3')
 assert.ok(Object.values(sent[0].p).every(x=>typeof x==='string'));assert.equal(sent[0].p.sig,signPushPayload('push-fixture',sent[0].p))
})
test('failed registration rolls back memory and reports retryable failure',async t=>{
 const f=await fixture(t);await mkdir(f.options.registryFile+'.tmp')
 assert.equal((await f.register()).status,503);assert.equal(f.app.devices.size,0)
})
test('event disk failure does not acknowledge, deduplicate or send',async t=>{
 let sends=0;const f=await fixture(t,async()=>sends++);await f.register();await mkdir(f.options.registryFile+'.tmp')
 assert.equal((await f.event('permission.asked',{id:'unique'})).status,503)
 assert.equal(f.app.states.size,0);assert.equal(f.app.outbox.length,0);assert.equal(sends,0)
 await rm(f.options.registryFile+'.tmp',{recursive:true})
 assert.equal((await f.event('permission.asked',{id:'unique'})).status,202);await until(()=>sends===1)
})
test('transient send is retried; accepted event is deduplicated across restart',async t=>{
 let sends=0;const f=await fixture(t,async()=>{if(++sends===1)throw Error('transient')});await f.register()
 await f.event('permission.asked',{id:'e1'});await until(()=>sends===2);await until(()=>f.app.outbox.length===0);await f.close()
 const recovered=createCompanion(f.options);await recovered.load();t.after(()=>recovered.close())
 assert.equal(recovered.states.size,1);assert.equal(recovered.outbox.length,0)
 const disk=JSON.parse(await readFile(f.options.registryFile,'utf8'));assert.ok(Object.keys(disk.accepted).length)
})
test('startup automatically resumes persisted pending delivery',async t=>{
 let recover=false, sent=0;const f=await fixture(t,async()=>{if(!recover)throw Error('offline');sent++},{retryDelayMs:100000});await f.register();await f.event('session.status',{status:'busy'})
 await until(()=>f.app.outbox[0]?.attempts===1);await f.close()
 const disk=JSON.parse(await readFile(f.options.registryFile,'utf8'));disk.pending[0].nextAttemptAt=0;await writeFile(f.options.registryFile,JSON.stringify(disk))
 recover=true;const restored=createCompanion({...f.options,retryDelayMs:5});await restored.load();t.after(()=>restored.close());await until(()=>sent===1)
})
test('new completion supersedes old failed waiting push',async t=>{
 const phases=[];let attempts=0;const f=await fixture(t,async(_t,p)=>{if(p.phase==='WAITING_PERMISSION'){attempts++;throw Error('offline')}phases.push(p.phase)},{retryDelayMs:100000})
 await f.register();await f.event('permission.asked');await until(()=>attempts===1)
 await f.event('session.idle');await until(()=>phases.length===1);await until(()=>f.app.outbox.length===0)
 assert.deepEqual(phases,['COMPLETED']);assert.equal(attempts,1)
})
test('withdrawal is durable and removes pending delivery',async t=>{
 let failed=true,sends=0;const f=await fixture(t,async()=>{if(failed)throw Error('offline');sends++},{retryDelayMs:100000});await f.register();await f.event('permission.asked');await until(()=>f.app.outbox[0]?.attempts===1)
 assert.equal((await f.post('/v1/devices/unregister',{deviceId:'device',serverKey:'srv'})).status,200)
 failed=false;await f.app.drainOutbox();assert.equal(sends,0);assert.equal(f.app.devices.size,0);assert.equal(f.app.outbox.length,0)
 const disk=JSON.parse(await readFile(f.options.registryFile,'utf8'));assert.equal(disk.devices.length,0);assert.equal(disk.pending.length,0)
})
test('failed withdrawal returns 503 and keeps previous registration',async t=>{
 const f=await fixture(t);await f.register();await mkdir(f.options.registryFile+'.tmp')
 assert.equal((await f.post('/v1/devices/unregister',{deviceId:'device',serverKey:'srv'})).status,503);assert.equal(f.app.devices.size,1)
})
test('active state survives restart and more than six hours',async t=>{
 const phases=[];const f=await fixture(t,async(_t,p)=>phases.push(p.phase));await f.register();await f.event('session.status',{status:'busy'});await until(()=>f.app.outbox.length===0);await f.close()
 const disk=JSON.parse(await readFile(f.options.registryFile,'utf8'));Object.values(disk.states).forEach(s=>s.at=Date.now()-7*3600000);await writeFile(f.options.registryFile,JSON.stringify(disk))
 const g=await fixture(t,f.options.send,{registryFile:f.options.registryFile});await g.event('session.idle');await until(()=>phases.includes('COMPLETED'))
})
test('pending permission blocks idle completion until its reply',async t=>{
 const phases=[];const f=await fixture(t,async(_t,p)=>phases.push(p.phase));await f.register()
 await f.event('permission.asked',{requestId:'p1'});await until(()=>phases.length===1)
 await f.event('session.idle');await sleep(30);assert.deepEqual(phases,['WAITING_PERMISSION'])
 await f.event('permission.replied',{requestId:'p1'});await f.event('session.idle');await until(()=>phases.includes('COMPLETED'))
})
test('capacity refuses new event instead of dropping an accepted delivery',async t=>{
 const f=await fixture(t,async()=>{throw Error('offline')},{maxOutbox:1,retryDelayMs:100000});await f.register()
 assert.equal((await f.event('permission.asked',{id:'e1'})).status,202)
 assert.equal((await f.event('permission.asked',{id:'e2',sessionId:'other'})).status,503)
 assert.equal(f.app.outbox.length,1);assert.equal(f.app.outbox[0].payload.sessionId,'ses')
})
test('expired registration cannot send its recovered queue',async t=>{
 const f=await fixture(t,async()=>{throw Error('offline')},{retryDelayMs:100000});await f.register();await f.event('permission.asked');await until(()=>f.app.outbox[0]?.attempts===1);await f.close()
 const disk=JSON.parse(await readFile(f.options.registryFile,'utf8'));disk.devices[0].updated=0;await writeFile(f.options.registryFile,JSON.stringify(disk))
 let sent=0;const restored=createCompanion({...f.options,send:async()=>sent++});await restored.load();t.after(()=>restored.close());await restored.drainOutbox();assert.equal(sent,0);assert.equal(restored.outbox.length,0)
})
test('producer high water rejects late input without growing per-event dedup history',async t=>{
 const f=await fixture(t);await f.event('session.status',{status:'busy',producerId:'host',sequence:2})
 await f.event('session.error',{producerId:'host',sequence:1})
 assert.equal([...f.app.states.values()][0].phase,'THINKING')
 const disk=JSON.parse(await readFile(f.options.registryFile,'utf8'));assert.equal(Object.keys(disk.accepted).length,1)
})
test('plugin spool retries HTTP failure and preserves identity across restart',async t=>{
 const dir=await mkdtemp('/tmp/opencode-plugin-test-');const file=dir+'/queue.json';const bodies=[]
 const first=await createEventForwarder({file,endpoint:'http://127.0.0.1',secret:'fixture',retryMs:100000,fetchImpl:async(_u,o)=>{bodies.push(JSON.parse(o.body));return {status:503}}})
 await first.enqueue({type:'session.idle',sessionId:'s',serverKey:'srv'});await first.drain();await first.close()
 const second=await createEventForwarder({file,endpoint:'http://127.0.0.1',secret:'fixture',fetchImpl:async(_u,o)=>{bodies.push(JSON.parse(o.body));return {status:202}}})
 await second.drain();await second.close();assert.equal(bodies[0].id,bodies[1].id);assert.equal(bodies[0].sequence,bodies[1].sequence)
 assert.equal(JSON.parse(await readFile(file,'utf8')).pending.length,0);await rm(dir,{recursive:true})
})
test('real plugin projection keeps private command/output out of the durable spool',async t=>{
 const dir=await mkdtemp('/tmp/opencode-plugin-projection-'), old={...process.env},oldFetch=globalThis.fetch,bodies=[]
 Object.assign(process.env,{OPENCODE_LAGOON_PLUGIN_QUEUE_DIR:dir,OPENCODE_LAGOON_COMPANION_URL:'http://127.0.0.1',OPENCODE_LAGOON_PLUGIN_SECRET:'fixture',OPENCODE_LAGOON_SERVER_KEY:'srv'})
 globalThis.fetch=async(_u,o)=>{bodies.push(JSON.parse(o.body));return {status:202}}
 try {
  const plugin=await OpenCodeLagoonPlugin({directory:'/repo'})
  await plugin.event({event:{type:'message.part.updated',properties:{sessionID:'s',part:{type:'tool',name:'bash',state:{input:{command:'gradle test PRIVATE'},output:'PRIVATE'}}}}})
  await until(()=>bodies.length===1);assert.equal(bodies[0].toolKind,'TESTING');assert.equal(JSON.stringify(bodies).includes('PRIVATE'),false)
  await sleep(30)
 }finally{globalThis.fetch=oldFetch;for(const key of ['OPENCODE_LAGOON_PLUGIN_QUEUE_DIR','OPENCODE_LAGOON_COMPANION_URL','OPENCODE_LAGOON_PLUGIN_SECRET','OPENCODE_LAGOON_SERVER_KEY']){if(old[key]==null)delete process.env[key];else process.env[key]=old[key]}await rm(dir,{recursive:true})}
})
