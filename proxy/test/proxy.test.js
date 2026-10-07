import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createProxy } from '../server.js';

const TOKEN = 'a-long-shared-proxy-token';
async function setup(handler, opts = {}) {
  const calls = [];
  const up = http.createServer((req, res) => { calls.push({ url: req.url, auth: req.headers.authorization }); handler(req, res); });
  await new Promise((r) => up.listen(0, r));
  const proxy = createProxy({ apiKey: 'REAL_KEY_VALUE', proxyToken: TOKEN, upstream: { host: '127.0.0.1', port: up.address().port, secure: false }, ...opts });
  await new Promise((r) => proxy.listen(0, r));
  const get = (path, token = TOKEN, method = 'GET') => new Promise((resolve, reject) => {
    const r = http.request({ host: '127.0.0.1', port: proxy.address().port, path, method, headers: token ? { authorization: `Bearer ${token}` } : {} }, (res) => {
      let b = ''; res.on('data', (c) => (b += c)); res.on('end', () => resolve({ status: res.statusCode, body: b, headers: res.headers }));
    }); r.on('error', reject); r.end();
  });
  return { calls, get, close: () => { proxy.close(); up.close(); } };
}
const ok = (_req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"name":"X","townHallLevel":14}'); };

test('forwards a player lookup with the real key and never leaks it', async () => {
  const s = await setup(ok);
  const r = await s.get('/v1/players/%232PP');
  assert.equal(r.status, 200);
  assert.match(r.body, /townHallLevel/);
  assert.equal(s.calls[0].auth, 'Bearer REAL_KEY_VALUE');
  assert.ok(!JSON.stringify(r).includes('REAL_KEY_VALUE'));
  s.close();
});

test('rejects missing/wrong proxy token', async () => {
  const s = await setup(ok);
  assert.equal((await s.get('/v1/players/%232PP', null)).status, 401);
  assert.equal((await s.get('/v1/players/%232PP', 'wrong-token-of-equal-len!')).status, 401);
  assert.equal(s.calls.length, 0);
  s.close();
});

test('only the player lookup is allowed; other paths and methods are refused', async () => {
  const s = await setup(ok);
  assert.equal((await s.get('/v1/clans/%232PP')).status, 403);
  assert.equal((await s.get('/v1/players/%232PP/battlelog')).status, 403);
  assert.equal((await s.get('/v1/players/%232PP', TOKEN, 'POST')).status, 405);
  assert.equal(s.calls.length, 0);
  s.close();
});

test('caches successful responses and respects the rate limit', async () => {
  const s = await setup(ok, { rateLimitPerMin: 3 });
  assert.equal((await s.get('/v1/players/%232PP')).headers['x-cache'], undefined);
  assert.equal((await s.get('/v1/players/%232PP')).headers['x-cache'], 'HIT');
  assert.equal(s.calls.length, 1);
  await s.get('/v1/players/%232PP');
  assert.equal((await s.get('/v1/players/%232PP')).status, 429);
  s.close();
});

test('upstream 403 becomes a clear IP hint; unreachable becomes 504; health is open', async () => {
  const s = await setup((_q, res) => { res.writeHead(403); res.end('{"reason":"accessDenied.invalidIp"}'); });
  const r = await s.get('/v1/players/%232PP');
  assert.equal(r.status, 502);
  assert.match(r.body, /IP/);
  assert.equal((await s.get('/health', null)).status, 200);
  s.close();
});

test('refuses to start without secrets', () => {
  assert.throws(() => createProxy({ apiKey: '', proxyToken: TOKEN }));
  assert.throws(() => createProxy({ apiKey: 'k', proxyToken: 'short' }));
});
