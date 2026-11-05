'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex').toUpperCase();

test('online installer pins the exact checked-in agent bytes', () => {
  const installer = read('install-online.ps1');
  const pinned = installer.match(/\$AgentSha256 = '([A-F0-9]{64})'/);
  assert.ok(pinned, 'installer needs an explicit SHA-256 pin');
  assert.equal(pinned[1], sha256('client/StandaloneAgent.ps1'));
});

test('online installer pins the shared installation core', () => {
  const pinned = read('install-online.ps1').match(/\$CoreSha256 = '([A-F0-9]{64})'/);
  assert.ok(pinned, 'downloaded install core needs an explicit SHA-256 pin');
  assert.equal(pinned[1], sha256('client/Install-Core.ps1'));
});

test('documented online command pins the exact installer bytes', () => {
  const pinned = read('README.md').match(/\$expected = '([A-F0-9]{64})'/);
  assert.ok(pinned, 'README needs a verified download command');
  assert.equal(pinned[1], sha256('install-online.ps1'));
});

test('documented release APK checksum matches the published file', () => {
  const readme = read('README.md');
  const apk = readme.match(/release\/(LanPowerStandalone-[\w.\-]+\.apk)/);
  assert.ok(apk, 'README needs a release APK path');
  const hash = readme.match(/SHA-256: ([A-F0-9]{64})/);
  assert.ok(hash, 'README needs a release APK checksum');
  assert.equal(hash[1], sha256(`release/${apk[1]}`));
});
