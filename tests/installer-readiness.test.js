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
const roomKey = 'Test1234'; // Disposable public fixture, never a deployment key.
const key = crypto.pbkdf2Sync(roomKey, 'LanPower/RoomKey/v1', 210000, 32, 'sha256').toString('base64');
const sign = (secret, text) => crypto.createHmac('sha256', Buffer.from(secret, 'base64')).update(text).digest('base64');

for (const scenario of ['valid-after-forgery', 'wrong-key', 'wrong-version', 'config-mismatch', 'media-ready', 'media-unavailable', 'media-forged']) {
  test(`installer readiness uses authenticated running-agent proof: ${scenario}`, { skip: process.platform !== 'win32', timeout: 15000 }, async t => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), 'roomtn20-ready-test-'));
    const server = dgram.createSocket('udp4');
    server.bind(0, '127.0.0.1');
    await once(server, 'listening');
    const port = server.address().port;
    const deviceId = crypto.randomUUID();
    await fs.writeFile(path.join(root, 'config.json'), JSON.stringify({ deviceId, managementKey: key, port,
      version: '1.3.3', versionCode: scenario === 'config-mismatch' ? 12 : 13 }));
    t.after(async () => { server.close(); await fs.rm(root, { recursive: true, force: true }); });
    let requests = 0;
    server.on('message', (bytes, remote) => {
      const request = JSON.parse(bytes);
      assert.equal(request.signature, sign(key, `discover\n${request.nonce}`));
      requests++;
      const replyKey = scenario === 'wrong-key' || (scenario === 'valid-after-forgery' && requests === 1)
        ? Buffer.alloc(32, 12).toString('base64') : key;
      const version = scenario === 'wrong-version' ? '1.3.2' : '1.3.3';
      const code = scenario === 'wrong-version' ? 12 : 13;
      const reply = { type: 'discovery', requestNonce: request.nonce, deviceId, hostname: 'READY-FIXTURE',
        mac: '02:00:00:00:00:01', agentVersion: version, uwf: 'OFF', capabilities: 'power,uwf,wolrelay',
        clientVersionCode: code, latestVersion: version, updateStatus: 'current', updateMessage: 'fixture' };
      reply.signature = sign(replyKey, `discovery\n${request.nonce}\n${deviceId}\n${reply.hostname}\n${reply.mac}`);
      reply.stateSignature = sign(replyKey, `state\n${request.nonce}\n${deviceId}\n${version}\n${reply.uwf}\n${reply.capabilities}`);
      reply.updateSignature = sign(replyKey, `update\n${request.nonce}\n${deviceId}\n${code}\n${version}\ncurrent\nfixture`);
      if (scenario.startsWith('media-')) {
        reply.capabilities += ',sessionlock,media';
        reply.stateSignature = sign(replyKey, `state\n${request.nonce}\n${deviceId}\n${version}\n${reply.uwf}\n${reply.capabilities}`);
        Object.assign(reply, { lockState: 'ui-offline', mediaVersion: 'none', mediaPort: 17842, mediaCertSha256: 'a'.repeat(64),
          runtimeStatus: scenario === 'media-unavailable' ? 'error' : 'ready', runtimeMessage: 'Fixture receiver status' });
        reply.lockSignature = sign(replyKey, `lockstate\n${request.nonce}\n${deviceId}\nui-offline\nnone\n17842\n${reply.mediaCertSha256}`);
        reply.runtimeSignature = sign(scenario === 'media-forged' ? Buffer.alloc(32, 1).toString('base64') : replyKey,
          `runtime\n${request.nonce}\n${deviceId}\n${reply.runtimeStatus}\n${reply.runtimeMessage}`);
      }
      server.send(Buffer.from(JSON.stringify(reply)), remote.port, remote.address);
    });
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(__dirname, 'InstalledAgent-Ready.ps1'), '-TestRoot', root,
      '-Port', String(port), '-RoomKey', roomKey, ...(scenario.startsWith('media-') ? ['-RequireMedia'] : [])], { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = ''; let stderr = '';
    child.stdout.on('data', bytes => { stdout += bytes; });
    child.stderr.on('data', bytes => { stderr += bytes; });
    t.after(() => { if (child.exitCode === null) child.kill(); });
    const [exitCode] = await once(child, 'exit');
    assert.ok(!stdout.includes(roomKey) && !stderr.includes(roomKey) && !stderr.includes(key), 'No key may leak in readiness results');
    if (scenario === 'valid-after-forgery' || scenario === 'media-ready') {
      assert.equal(exitCode, 0, stderr);
      assert.match(stdout, /INSTALLED_AGENT_READY=PASS/);
      assert.ok(requests >= (scenario === 'valid-after-forgery' ? 2 : 1), 'Startup response must prove readiness');
    } else {
      assert.notEqual(exitCode, 0, stdout);
      assert.doesNotMatch(stdout, /INSTALLED_AGENT_READY=PASS/);
      assert.match(stderr, scenario === 'config-mismatch' ? /CLIENT_CONFIG_VERIFY_FAILED/ : scenario.startsWith('media-') ? /MEDIA_RECEIVER_NOT_READY/ : /CLIENT_NOT_RESPONDING/);
      if (scenario === 'config-mismatch') assert.equal(requests, 0);
    }
  });
}
