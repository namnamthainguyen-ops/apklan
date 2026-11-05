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
  value.signature = sign(key, `command\n${value.requestId}\n${value.deviceId}\n${value.action}\n${value.timestamp}\n${value.nonce}`);
  return value;
}
async function startAgent(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'lanpower-agent-test-'));
  const socket = dgram.createSocket('udp4');
  socket.bind(0, '127.0.0.1'); await once(socket, 'listening');
  const port = socket.address().port;
  socket.close(); await once(socket, 'close');
  const key = crypto.randomBytes(32).toString('base64');
  const deviceId = crypto.randomUUID();
  await fs.writeFile(path.join(root, 'config.json'), JSON.stringify({ managementKey: key, deviceId, port }));
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

test('real agent ignores malformed, oversized, unsigned and wrongly signed discovery; accepts valid HMAC', { skip: process.platform !== 'win32', timeout: 30000 }, async t => {
  const agent = await startAgent(t);
  for (const input of [Buffer.from('{bad json'), Buffer.alloc(9000, 65), {}, { type: 'discover', nonce: 'test' }, { type: 'discover', nonce: 'test', signature: 'not base64' }]) {
    assert.equal(await agent.exchange(input), null);
  }
  const nonce = crypto.randomUUID();
  const reply = await agent.exchange({ type: 'discover', nonce, signature: sign(agent.key, `discover\n${nonce}`) }, 3000);
  assert.equal(reply.type, 'discovery'); assert.equal(reply.requestNonce, nonce); assert.equal(reply.deviceId, agent.deviceId);
  assert.equal(reply.signature, sign(agent.key, `discovery\n${nonce}\n${reply.deviceId}\n${reply.hostname}\n${reply.mac}`));
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
    const [code] = await once(agent.child, 'exit');
    assert.equal(code, 0, agent.errors());
    assert.match(agent.output(), new RegExp(`TEST_POWER_ACTION=${action}`));
  });
}
