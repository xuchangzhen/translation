const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
// Exercise storage without requiring a GUI/keychain in the Node test runner.
const moduleStub = { exports: {} };
const safeStorage = { isEncryptionAvailable: () => true, encryptString: text => Buffer.from(`encrypted:${text}`), decryptString: bytes => { const text = bytes.toString(); if (!text.startsWith('encrypted:')) throw Error('bad'); return text.slice(10); } };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/lib/settings.cjs'), 'utf8'), {
  module: moduleStub, require: name => name === 'electron' ? { safeStorage } : require(name.startsWith('./') ? `../src/lib/${name.slice(2)}` : name), Buffer, process
});
const { SettingsStore } = moduleStub.exports;
function fixture(run, legacy) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-keys-'));
  try { if (legacy) fs.writeFileSync(path.join(dir, 'settings.json'), JSON.stringify(legacy)); run(new SettingsStore(dir), dir); }
  finally { fs.rmSync(dir, { recursive: true, force: true }); }
}
test('each provider key survives switching and clearing another provider', () => fixture((store, dir) => {
  for (const provider of ['openai', 'google', 'compatible']) store.update({ provider, apiKey: `${provider}-secret` });
  const reloaded = new SettingsStore(dir);
  for (const provider of ['openai', 'google', 'compatible']) assert.equal(reloaded.apiKey(provider), `${provider}-secret`);
  reloaded.update({ clearApiKey: true, apiKeyProvider: 'google' });
  assert.equal(reloaded.apiKey('google'), ''); assert.equal(reloaded.apiKey('compatible'), 'compatible-secret');
  const publicSettings = JSON.stringify(reloaded.publicValue());
  assert.ok(!publicSettings.includes('Encrypted')); assert.ok(!publicSettings.includes('secret'));
  assert.equal(reloaded.publicValue().apiKey, ''); assert.equal(reloaded.publicValue().apiKeyConfiguredByProvider.openai, true);
  assert.ok(!fs.readFileSync(path.join(dir, 'settings.json'), 'utf8').includes('compatible-secret'));
}));
test('legacy ciphertext migrates only to its selected provider', () => fixture(store => {
  assert.equal(store.apiKey('openai'), 'old-secret'); assert.equal(store.apiKey('compatible'), '');
  store.update({ provider: 'compatible' }); assert.equal(store.apiKey(), '');
  store.update({ provider: 'openai' }); assert.equal(store.apiKey(), 'old-secret');
}, { provider: 'openai', apiKeyEncrypted: Buffer.from('encrypted:old-secret').toString('base64') }));
test('unreadable legacy ciphertext and unknown ownership do not crash or leak', () => fixture(store => {
  assert.equal(store.apiKey(), ''); assert.equal(store.publicValue().apiKey, ''); assert.equal(store.publicValue().apiKeyEncrypted, undefined);
}, { provider: 'ollama', apiKeyEncrypted: 'unreadable' }));

test('encryption failure leaves in-memory and persisted settings unchanged', () => fixture((store, dir) => {
  store.update({ provider: 'openai', apiKey: 'saved-secret' });
  const before = fs.readFileSync(path.join(dir, 'settings.json'), 'utf8');
  const encrypt = safeStorage.encryptString;
  safeStorage.encryptString = () => { throw new Error('keychain unavailable'); };
  try { assert.throws(() => store.update({ provider: 'compatible', apiKey: 'new-secret' }), /keychain unavailable/); }
  finally { safeStorage.encryptString = encrypt; }
  assert.equal(store.data.provider, 'openai'); assert.equal(store.apiKey(), 'saved-secret');
  assert.equal(store.apiKey('compatible'), '');
  assert.equal(fs.readFileSync(path.join(dir, 'settings.json'), 'utf8'), before);
}));

test('save failure rolls back in-memory settings', () => fixture(store => {
  store.update({ provider: 'google' });
  store.save = () => { throw new Error('disk unavailable'); };
  assert.throws(() => store.update({ provider: 'compatible', apiKey: 'new-secret' }), /disk unavailable/);
  assert.equal(store.data.provider, 'google'); assert.equal(store.apiKey('compatible'), '');
}));
