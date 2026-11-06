'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

test('production atomic file writer supports first install, reinstall and repeated status updates on PowerShell 5.1', { skip: process.platform !== 'win32', timeout: 30000 }, () => {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, 'Atomic-File.ps1')], { encoding: 'utf8', timeout: 25000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /ATOMIC_FILE_WRITE=PASS/);
});

test('production signed updater rejects tampering, untrusted sources and downgrade without OS changes', { skip: process.platform !== 'win32', timeout: 60000 }, () => {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, 'Updater-Unit.ps1')], { encoding: 'utf8', timeout: 55000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /SIGNED_UPDATER_UNIT=PASS/);
});

test('production updater applies only verified bytes, preserves configuration and rolls back in an isolated workspace harness', { skip: process.platform !== 'win32', timeout: 60000 }, () => {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, 'Updater-Apply.ps1')], { encoding: 'utf8', timeout: 55000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /SIGNED_UPDATER_APPLY=PASS/);
});
