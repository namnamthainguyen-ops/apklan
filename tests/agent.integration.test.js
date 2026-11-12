'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const dgram = require('node:dgram');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');

function sign(key, value) { return crypto.createHmac('sha256', Buffer.from(key, 'base64')).update(value).digest('base64'); }
function command(key, deviceId, overrides = {}) {
  const value = { type: 'command', requestId: crypto.randomUUID(), deviceId, action: 'shutdown', timestamp: Math.floor(Date.now() / 1000), nonce: crypto.randomUUID(), ...overrides };
  const canonical = `command\n${value.requestId}\n${value.deviceId}\n${value.action}\n${value.timestamp}\n${value.nonce}`;
  value.signature = sign(key, canonical + (value.action === 'wake' ? `\n${value.targetMac}` : ''));
  return value;
}
async function startAgent(t, options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'lanpower-agent-test-'));
  const socket = dgram.createSocket('udp4');
  socket.bind(0, '127.0.0.1'); await once(socket, 'listening');
  const port = socket.address().port;
  socket.close(); await once(socket, 'close');
  const key = options.roomKey ? crypto.pbkdf2Sync(options.roomKey, 'LanPower/RoomKey/v1', 210000, 32, 'sha256').toString('base64') : crypto.randomBytes(32).toString('base64');
  const deviceId = options.savedDeviceId || crypto.randomUUID();
  await fs.writeFile(path.join(root, 'config.json'), JSON.stringify({ managementKey: key, deviceId, port, ...(options.hardwareUuid ? { identityMode: 'hardware-v1' } : {}) }));
  if (options.hardwareUuid) await fs.writeFile(path.join(root, 'hardware-uuid'), options.hardwareUuid);
  if (options.uwfFailure) await fs.writeFile(path.join(root, 'fail-uwf'), '1');
  if (options.lockFailure) await fs.writeFile(path.join(root, 'fail-lock'), '1');
  const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'Agent-Harness.ps1'), '-AgentPath', path.join(__dirname, '..', 'client', 'StandaloneAgent.ps1'), '-TestRoot', root], { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = ''; let errors = '';
  child.stderr.on('data', chunk => { errors += chunk; });
  child.stdout.on('data', chunk => { output += chunk; });
  t.after(async () => {
    if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
    await fs.rm(root, { recursive: true, force: true });
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Agent startup timed out: ${output} ${errors}`)), 15000);
    child.stdout.on('data', () => { if (output.includes('Listening on UDP')) { clearTimeout(timer); resolve(); } });
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('exit', code => { clearTimeout(timer); reject(new Error(`Agent exited at startup: ${code} ${errors}`)); });
  });
  async function exchange(value, wait = 400) {
    const client = dgram.createSocket('udp4');
    const bytes = Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { client.close(); resolve(null); }, wait);
      client.once('error', error => { clearTimeout(timer); client.close(); reject(error); });
      client.once('message', bytes => { clearTimeout(timer); client.close(); resolve(JSON.parse(bytes)); });
      client.send(bytes, port, '127.0.0.1');
    });
  }
  return { key, deviceId, child, exchange, output: () => output, errors: () => errors };
}

test('ghosted agents keep their room key but advertise distinct stable hardware identities', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const common = { roomKey: 'Ghost123', savedDeviceId: crypto.randomUUID() };
  const a = await startAgent(t, { ...common, hardwareUuid: 'HP-TEST-001' });
  const b = await startAgent(t, { ...common, hardwareUuid: 'HP-TEST-002' });
  const rebooted = await startAgent(t, { ...common, hardwareUuid: 'HP-TEST-001' });
  async function discover(agent) {
    const nonce = crypto.randomUUID();
    const reply = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
    assert.ok(reply);
    assert.equal(reply.signature, sign(agent.key, `discovery\n${nonce}\n${reply.deviceId}\n${reply.hostname}\n${reply.mac}`));
    return reply.deviceId;
  }
  const first = await discover(a), second = await discover(b);
  assert.notEqual(first, second); assert.notEqual(first, common.savedDeviceId);
  assert.equal(await discover(rebooted), first);
  assert.equal(a.key, b.key);
});

test('real agent ignores malformed, oversized, unsigned and wrongly signed discovery; accepts valid HMAC', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t);
  for (const input of [Buffer.from('{bad json'), Buffer.alloc(9000, 65), {}, { type: 'discover', nonce: 'test' }, { type: 'discover', nonce: 'test', signature: 'not base64' }]) {
    assert.equal(await agent.exchange(input), null);
  }
  const nonce = crypto.randomUUID();
  const reply = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
  assert.equal(reply.type, 'discovery'); assert.equal(reply.requestNonce, nonce); assert.equal(reply.deviceId, agent.deviceId);
  assert.equal(reply.signature, sign(agent.key, `discovery\n${nonce}\n${reply.deviceId}\n${reply.hostname}\n${reply.mac}`));
  assert.deepEqual(new Set(reply.capabilities.split(',')), new Set(['power', 'uwf', 'wolrelay', 'sessionlock', 'media', 'mediasync']));
  assert.equal(reply.stateSignature, sign(agent.key, `state\n${nonce}\n${reply.deviceId}\n${reply.agentVersion}\n${reply.uwf}\n${reply.capabilities}`));
  assert.equal(reply.lockSignature, sign(agent.key, `lockstate\n${nonce}\n${reply.deviceId}\n${reply.lockState}\n${reply.mediaVersion}\n${reply.mediaPort}\n${reply.mediaCertSha256}`));
  assert.equal(reply.clientVersionCode, 15);
  assert.equal(reply.runtimeStatus, 'ready');
  assert.equal(reply.runtimeSignature, sign(agent.key, `runtime\n${nonce}\n${reply.deviceId}\n${reply.runtimeStatus}\n${reply.runtimeMessage}`));
  assert.equal(reply.updateSignature, sign(agent.key, `update\n${nonce}\n${reply.deviceId}\n${reply.clientVersionCode}\n${reply.latestVersion}\n${reply.updateStatus}\n${reply.updateMessage}`));
});

test('real agent rejects forged, stale, future and wrong-device commands; prevents nonce replay', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t);
  const unauthorized = command(crypto.randomBytes(32).toString('base64'), agent.deviceId);
  const stale = command(agent.key, agent.deviceId, { timestamp: Math.floor(Date.now() / 1000) - 121 });
  const future = command(agent.key, agent.deviceId, { timestamp: Math.floor(Date.now() / 1000) + 300 });
  const wrongDevice = command(agent.key, crypto.randomUUID());
  for (const input of [unauthorized, stale, future, wrongDevice, { type: 'command' }]) {
    assert.equal(await agent.exchange(input), null);
  }
  const unsupported = command(agent.key, agent.deviceId, { action: 'format-disk' });
  const reply = await agent.exchange(unsupported, 3000);
  assert.equal(reply.status, 'rejected');
  assert.equal(reply.signature, sign(agent.key, `ack\n${unsupported.requestId}\nrejected`));
  assert.equal(reply.detailSignature, sign(agent.key, `ack-detail\n${unsupported.requestId}\nrejected\n${reply.message}`));
  assert.equal(await agent.exchange(unsupported), null, 'replayed authenticated request must be ignored');
  assert.doesNotMatch(agent.output(), /TEST_POWER_ACTION/);
});

for (const action of ['shutdown', 'restart']) {
  test(`real agent authenticates ${action} and invokes only stubbed power function`, { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
    const agent = await startAgent(t);
    const request = command(agent.key, agent.deviceId, { action });
    const reply = await agent.exchange(request, 3000);
    assert.equal(reply.status, 'accepted');
    assert.equal(reply.signature, sign(agent.key, `ack\n${request.requestId}\naccepted`));
    const code = agent.child.exitCode !== null ? agent.child.exitCode : (await once(agent.child, 'exit'))[0];
    assert.equal(code, 0, agent.errors());
    assert.match(agent.output(), new RegExp(`TEST_POWER_ACTION=${action}`));
  });
}

test('room keys isolate discovery and commands between rooms', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t, { roomKey: 'RoomA123' });
  const otherKey = crypto.pbkdf2Sync('RoomB456', 'LanPower/RoomKey/v1', 210000, 32, 'sha256').toString('base64');
  const nonce = crypto.randomUUID();
  assert.equal(await agent.exchange({ type: 'discover', nonce, signature: sign(otherKey, `discover\n${nonce}`) }), null);
  assert.equal(await agent.exchange(command(otherKey, agent.deviceId, { action: 'freeze' })), null);
  const reply = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
  assert.equal(reply.deviceId, agent.deviceId);
  assert.doesNotMatch(agent.output(), /TEST_UWF_ACTION/);
});

test('freeze and unfreeze execute only safe stubs, sign outcome and keep agent alive', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t);
  for (const [action, state] of [['freeze', 'OFF (next ON)'], ['unfreeze', 'ON (next OFF)']]) {
    const request = command(agent.key, agent.deviceId, { action });
    const reply = await agent.exchange(request, 3000);
    assert.equal(reply.status, 'accepted');
    assert.match(reply.message, /Restart required/);
    assert.equal(reply.detailSignature, sign(agent.key, `ack-detail\n${request.requestId}\naccepted\n${reply.message}`));
    assert.match(agent.output(), new RegExp(`TEST_UWF_ACTION=${action}`));
    assert.equal(agent.child.exitCode, null, 'UWF must not stop the listening loop');
    const nonce = crypto.randomUUID();
    const status = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
    assert.equal(status.uwf, state);
    assert.equal(status.stateSignature, sign(agent.key, `state\n${nonce}\n${status.deviceId}\n${status.agentVersion}\n${state}\n${status.capabilities}`));
    assert.equal(await agent.exchange(request), null, 'UWF request replay must not reapply changes');
  }
});

test('failed UWF operation returns signed rejection and agent remains available', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t, { uwfFailure: true });
  const request = command(agent.key, agent.deviceId, { action: 'freeze' });
  const reply = await agent.exchange(request, 3000);
  assert.equal(reply.status, 'rejected');
  assert.match(reply.message, /TEST_UWF_FAILURE/);
  assert.equal(reply.detailSignature, sign(agent.key, `ack-detail\n${request.requestId}\nrejected\n${reply.message}`));
  const nonce = crypto.randomUUID();
  assert.equal((await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000)).deviceId, agent.deviceId);
  assert.doesNotMatch(agent.output(), /TEST_POWER_ACTION|TEST_UWF_ACTION/);
});

test('authenticated VPN Wake-on-LAN relay binds the target MAC and leaves the agent listening', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t);
  const forged = command(agent.key, agent.deviceId, { action: 'wake', targetMac: '02:00:00:00:00:01' });
  forged.targetMac = '02:00:00:00:00:02';
  assert.equal(await agent.exchange(forged), null, 'changing target MAC must invalidate relay signature');
  const invalid = command(agent.key, agent.deviceId, { action: 'wake', targetMac: 'not-a-mac' });
  assert.equal(await agent.exchange(invalid), null, 'malformed relay MAC must be ignored before action dispatch');
  assert.doesNotMatch(agent.output(), /TEST_WAKE_RELAY/);
  const request = command(agent.key, agent.deviceId, { action: 'wake', targetMac: '02:00:00:00:00:01' });
  const reply = await agent.exchange(request, 3000);
  assert.equal(reply.status, 'accepted');
  assert.equal(reply.detailSignature, sign(agent.key, `ack-detail\n${request.requestId}\naccepted\n${reply.message}`));
  assert.match(agent.output(), /TEST_WAKE_RELAY=02:00:00:00:00:01/);
  assert.equal(await agent.exchange(request), null, 'relay replay must not send another magic packet');
  const nonce = crypto.randomUUID();
  assert.equal((await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000)).deviceId, agent.deviceId);
});

test('lock and unlock require authentication, confirm signed display state and never invoke real interactive UI', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t);
  for (const [action, expectedState] of [['lock', 'locked'], ['unlock', 'unlocked']]) {
    const forged = command(crypto.randomBytes(32).toString('base64'), agent.deviceId, { action });
    assert.equal(await agent.exchange(forged), null);
    const request = command(agent.key, agent.deviceId, { action });
    const reply = await agent.exchange(request, 3000);
    assert.equal(reply.status, 'accepted');
    assert.equal(reply.detailSignature, sign(agent.key, `ack-detail\n${request.requestId}\naccepted\n${reply.message}`));
    const nonce = crypto.randomUUID();
    const state = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
    assert.equal(state.lockState, expectedState);
    assert.equal(state.lockSignature, sign(agent.key, `lockstate\n${nonce}\n${state.deviceId}\n${expectedState}\n${state.mediaVersion}\n${state.mediaPort}\n${state.mediaCertSha256}`));
    assert.match(agent.output(), new RegExp(`TEST_SESSION_LOCK=${action}`));
    assert.equal(await agent.exchange(request), null, 'session-lock replay must not execute again');
  }
  assert.doesNotMatch(agent.output(), /TEST_POWER_ACTION/);
});

test('missing interactive display returns signed lock failure without claiming a locked screen', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t, { lockFailure: true });
  const request = command(agent.key, agent.deviceId, { action: 'lock' });
  const reply = await agent.exchange(request, 3000);
  assert.equal(reply.status, 'rejected');
  assert.match(reply.message, /TEST_LOCK_UI_UNAVAILABLE/);
  assert.equal(reply.detailSignature, sign(agent.key, `ack-detail\n${request.requestId}\nrejected\n${reply.message}`));
  const nonce = crypto.randomUUID();
  const state = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
  assert.equal(state.lockState, 'unlocked');
  assert.doesNotMatch(agent.output(), /TEST_SESSION_LOCK|TEST_POWER_ACTION/);
});
