'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
test('ghost identity follows hardware while installer and runtime agree', { skip: process.platform !== 'win32' }, () => {
  const result = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(__dirname, 'Hardware-Identity.ps1')], { encoding: 'utf8', timeout: 15000 });
  assert.match(result, /HARDWARE_IDENTITY=PASS/);
});
