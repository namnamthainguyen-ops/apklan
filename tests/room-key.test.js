'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

function derive(roomKey) {
  return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, 'Key-Derivation.ps1'), '-RoomKey', roomKey], { encoding: 'utf8', timeout: 15000 });
}
const expected = key => crypto.pbkdf2Sync(key, 'LanPower/RoomKey/v1', 210000, 32, 'sha256').toString('base64');

test('production PowerShell room key derivation matches independent Node PBKDF2-HMACSHA256', { skip: process.platform !== 'win32' }, () => {
  for (const key of ['RoomA123', 'rooma123', '12345678', 'ab!2@Cd?']) {
    const result = derive(key);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), expected(key));
    assert.equal(Buffer.from(result.stdout.trim(), 'base64').length, 32);
  }
  assert.notEqual(expected('RoomA123'), expected('rooma123'), 'room keys must remain case-sensitive');
});

test('production room key derivation refuses wrong lengths, whitespace and non-ASCII input', { skip: process.platform !== 'win32' }, () => {
  for (const key of ['1234567', '123456789', 'Room 123', 'Roomé123']) {
    const result = derive(key);
    assert.notEqual(result.status, 0, `invalid key was accepted: ${key}`);
  }
});
