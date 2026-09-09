const test = require('node:test');
const assert = require('node:assert/strict');
const { compatibleBaseUrl, compatibleChat, compatibleModels } = require('../src/lib/compatible.cjs');
const { translateText, enrichTranslation, testProvider } = require('../src/lib/translator.cjs');
const settings = { provider: 'compatible', compatibleBaseUrl: 'https://relay.example/v1///', compatibleModel: 'relay-model', targetLanguage: 'zh-CN' };
const reply = (status, body) => ({ ok: status >= 200 && status < 300, status, text: async () => typeof body === 'string' ? body : JSON.stringify(body) });
async function mockFetch(fn, run) { const previous = global.fetch; global.fetch = fn; try { await run(); } finally { global.fetch = previous; } }

test('normalizes base URLs and strips complete endpoint paths without double appending', () => {
  for (const value of [' https://relay.example/v1/// ', 'https://relay.example/v1/chat/completions/', 'https://relay.example/v1/models']) assert.equal(compatibleBaseUrl(value), 'https://relay.example/v1');
  for (const value of ['bad', 'file:///tmp/key', 'https://user:secret@relay.example/v1', 'https://relay.example/v1?key=secret']) assert.throws(() => compatibleBaseUrl(value), /Base URL/);
});
test('GET models authenticates and parses unique IDs', async () => {
  await mockFetch(async (url, init) => {
    assert.equal(url, 'https://relay.example/v1/models'); assert.equal(init.method, 'GET');
    assert.equal(init.headers.Authorization, 'Bearer test-secret');
    return reply(200, { data: [{ id: 'b' }, { id: 'a' }, { id: 'b' }, {}, { id: 42 }] });
  }, async () => assert.deepEqual(await compatibleModels(settings, 'test-secret'), ['a', 'b']));
});
test('missing model catalog remains optional', async () => {
  await mockFetch(async () => reply(404, '<html>secret</html>'), async () => {
    await assert.rejects(compatibleModels(settings, ''), /请手动填写模型 ID/);
  });
});
for (const parameter of ['reasoning_effort', 'response_format']) {
  test(`retries unsupported ${parameter} with only basic fields`, async () => {
    let calls = 0;
    await mockFetch(async (url, init) => {
      assert.equal(url, 'https://relay.example/v1/chat/completions');
      assert.equal(init.headers.Authorization, 'Bearer test-secret'); assert.equal(init.method, 'POST');
      const body = JSON.parse(init.body); calls++;
      if (calls === 1) { assert.ok(body[parameter]); return reply(400, { error: { message: `Unknown parameter: ${parameter}` } }); }
      assert.deepEqual(Object.keys(body).sort(), ['messages', 'model', 'temperature']);
      return reply(200, { choices: [{ message: { content: '{"translation":"缓存"}' } }] });
    }, async () => assert.equal((await translateText('cache', settings, 'test-secret')).translation, '缓存'));
    assert.equal(calls, 2);
  });
}
test('enrichment uses same compatibility fallback', async () => {
  let calls = 0;
  await mockFetch(async () => ++calls === 1 ? reply(400, { error: { message: 'response_format not supported' } }) : reply(200, { choices: [{ message: { content: '{"explanation":"临时存储","terms":[]}' } }] }), async () => {
    assert.equal((await enrichTranslation('cache', '缓存', settings, '')).explanation, '临时存储');
  }); assert.equal(calls, 2);
});
for (const [status, expected] of [[401, 'API Key 无效或没有权限'], [403, 'API Key 无效或没有权限'], [404, 'API 地址或接口路径错误'], [429, '请求频率或账户额度受限'], [503, '中转服务暂时不可用'], [400, 'API 请求失败']]) {
  test(`normalizes ${status} without leaking body or retrying`, async () => {
    let calls = 0;
    await mockFetch(async () => { calls++; return reply(status, { error: { message: 'secret token <html>reasoning_effort invalid model</html>' } }); }, async () => {
      await assert.rejects(compatibleChat(settings, 'test-secret', []), error => error.message.includes(expected) && !error.message.includes('secret'));
    }); assert.equal(calls, 1);
  });
}
test('network errors hide potentially sensitive details', async () => {
  await mockFetch(async () => { throw new TypeError('fetch failed secret'); }, async () => {
    await assert.rejects(compatibleChat(settings, '', []), /无法连接 API/);
  });
});
test('connection test makes a small real chat request', async () => {
  await mockFetch(async (url, init) => {
    const body = JSON.parse(init.body); assert.equal(body.messages.length, 1); assert.ok(body.messages[0].content.length < 30);
    assert.equal(body.response_format, undefined);
    return reply(200, { choices: [{ message: { content: 'OK' } }] });
  }, async () => { const result = await testProvider(settings, ''); assert.equal(result.ok, true); assert.equal(result.model, 'relay-model'); assert.ok(result.latencyMs >= 0); });
});

test('timeout produces the friendly timeout message', async () => {
  const originalTimer = global.setTimeout;
  global.setTimeout = callback => originalTimer(callback, 1);
  try {
    await mockFetch(async (_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }), async () => await assert.rejects(compatibleChat(settings, '', []), /请求超时，请检查网络或中转站状态/));
  } finally { global.setTimeout = originalTimer; }
});

test('structured unsupported parameter codes trigger a single fallback', async () => {
  let calls = 0;
  await mockFetch(async () => ++calls === 1
    ? reply(400, { error: { param: 'reasoning_effort', code: 'unsupported_parameter', message: 'This parameter is unavailable.' } })
    : reply(200, { choices: [{ message: { content: 'OK' } }] }), async () => {
    await compatibleChat(settings, '', []);
  });
  assert.equal(calls, 2);
});

test('unrelated unsupported model errors do not trigger extension fallback', async () => {
  let calls = 0;
  await mockFetch(async () => { calls++; return reply(400, { error: { message: 'Unsupported model. Request included reasoning_effort.' } }); }, async () => {
    await assert.rejects(compatibleChat(settings, '', []), /API 请求失败/);
  });
  assert.equal(calls, 1);
});
