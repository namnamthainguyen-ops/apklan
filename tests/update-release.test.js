'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file));

test('deployed update public keys agree and contain no private RSA parameters', () => {
  const xml = read('updates/public-key.xml').toString('utf8');
  assert.doesNotMatch(xml, /<(D|P|Q|DP|DQ|InverseQ)>/);
  assert.equal(read('client/Update-PublicKey.xml').toString('utf8'), xml);
  const json = JSON.parse(read('updates/public-key.json'));
  assert.equal(json.algorithm, 'RS256');
  assert.equal(json.modulus, xml.match(/<Modulus>([^<]+)<\/Modulus>/)[1]);
  assert.equal(json.exponent, xml.match(/<Exponent>([^<]+)<\/Exponent>/)[1]);
  const androidTrust = read('android-app/app/src/main/java/com/lanpower/standalone/UpdateTrust.java').toString('utf8');
  assert.ok(androidTrust.includes(json.modulus), 'Android must embed the same offline trust key');
  assert.ok(Buffer.from(json.modulus, 'base64').length >= 384, 'Update trust key must be at least RSA 3072');
});

test('published stable update manifest is signed and pins exact release files in an immutable commit', {
  skip: !fs.existsSync(path.join(root, 'updates/stable.json'))
}, () => {
  const envelope = JSON.parse(read('updates/stable.json'));
  const trust = JSON.parse(read('updates/public-key.json'));
  assert.equal(envelope.algorithm, 'RS256');
  const publicKey = crypto.createPublicKey({
    key: { kty: 'RSA', n: Buffer.from(trust.modulus, 'base64').toString('base64url'), e: Buffer.from(trust.exponent, 'base64').toString('base64url') },
    format: 'jwk'
  });
  const payload = Buffer.from(envelope.payload, 'base64');
  assert.ok(crypto.verify('RSA-SHA256', payload, publicKey, Buffer.from(envelope.signature, 'base64')));
  const altered = Buffer.from(payload);
  altered[0] ^= 1;
  assert.equal(crypto.verify('RSA-SHA256', altered, publicKey, Buffer.from(envelope.signature, 'base64')), false);
  const manifest = JSON.parse(payload.toString('utf8'));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.channel, 'stable');
  assert.equal(manifest.android.packageName, 'com.lanpower.standalone');
  const androidMetadata = read('android-app/app/build.gradle').toString('utf8');
  const windowsMetadata = read('client/Install-Core.ps1').toString('utf8');
  assert.equal(manifest.android.versionCode, Number(androidMetadata.match(/versionCode\s+(\d+)/)[1]));
  assert.equal(manifest.android.version, androidMetadata.match(/versionName\s+'([^']+)'/)[1]);
  assert.equal(manifest.windows.versionCode, Number(windowsMetadata.match(/versionCode = (\d+)/)[1]));
  assert.equal(manifest.windows.version, windowsMetadata.match(/version = '([^']+)'/)[1]);
  for (const artifact of [manifest.android, ...['agent', 'core', 'updater', 'runtime', 'player', 'setup'].map(name => manifest.windows[name])]) {
    const match = artifact.url.match(/^https:\/\/raw\.githubusercontent\.com\/namnamthainguyen-ops\/apklan\/([a-f0-9]{40})\/(client|release)\/([A-Za-z0-9_.-]+)$/);
    assert.ok(match, 'Release files must use immutable repository commit URLs');
    const actual = crypto.createHash('sha256').update(read(`${match[2]}/${match[3]}`)).digest('hex');
    assert.equal(actual, artifact.sha256.toLowerCase());
  }
});
