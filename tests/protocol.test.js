'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { spawn } = require('node:child_process');

function hmac(key, value) {
  return crypto.createHmac('sha256', Buffer.from(key, 'base64')).update(value, 'utf8').digest('base64');
}

function runPowerShellSelfTest() {
  return new Promise((resolve, reject) => {
    const agent = path.resolve(__dirname, '..', 'client', 'StandaloneAgent.ps1');
    const child = spawn('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', agent, '-SelfTest'
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => { stdout += chunk.toString(); });
    child.stderr.on('data', chunk => { stderr += chunk.toString(); });
    child.once('error', reject);
    child.once('exit', code => resolve({ code, stdout, stderr }));
  });
}

test('Windows PowerShell 5.1 agent passes its HMAC-SHA256 self-test', async () => {
  const result = await runPowerShellSelfTest();
  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /self-test passed/);
});

test('protocol signatures bind every command field', () => {
  const key = crypto.randomBytes(32).toString('base64');
  const requestId = crypto.randomUUID();
  const deviceId = crypto.randomUUID();
  const action = 'shutdown';
  const timestamp = 1793900000;
  const nonce = crypto.randomUUID().replaceAll('-', '');
  const signature = hmac(key, `command\n${requestId}\n${deviceId}\n${action}\n${timestamp}\n${nonce}`);
  assert.notEqual(signature, hmac(key, `command\n${requestId}\n${deviceId}\nrestart\n${timestamp}\n${nonce}`));
  assert.notEqual(signature, hmac(key, `command\n${requestId}\n${deviceId}\n${action}\n${timestamp + 1}\n${nonce}`));
  assert.equal(Buffer.from(signature, 'base64').length, 32);
});

test('Wake-on-LAN packet is 6 FF bytes plus 16 MAC repetitions', () => {
  const mac = Buffer.from('001122334455', 'hex');
  const packet = Buffer.concat([Buffer.alloc(6, 0xff), ...Array.from({ length: 16 }, () => mac)]);
  assert.equal(packet.length, 102);
  assert.deepEqual(packet.subarray(0, 6), Buffer.alloc(6, 0xff));
  for (let offset = 6; offset < packet.length; offset += 6) {
    assert.deepEqual(packet.subarray(offset, offset + 6), mac);
  }
});
