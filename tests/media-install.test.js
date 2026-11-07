'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

test('production media installer verifies pinned binaries, stable TLS identity and isolated data-drive policy without installing', { skip: process.platform !== 'win32', timeout: 30000 }, () => {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, 'Media-Install-Policy.ps1')], { encoding: 'utf8', timeout: 25000 });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /MEDIA_INSTALL_POLICY=PASS/);
});
