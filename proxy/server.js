#!/usr/bin/env node
// ledger-proxy: tiny forwarding proxy so a phone can use the official API.
// The official API ties each key to ONE IP address, so run this on a machine with a stable public IP
// and register that IP on your key. The app never sees the real key; it sends PROXY_TOKEN instead.
import http from 'node:http';
import https from 'node:https';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const UPSTREAM_HOST = 'api.clashofclans.com';

/** Only read-only player lookups are forwarded. Everything else is refused. */
export const ALLOWED = [/^\/v1\/players\/%23[0289PYLQGRJCUVpylqgrjcuv]{3,15}$/];

export function safeEqual(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && timingSafeEqual(x, y);
}

export function createProxy({
  apiKey,
  proxyToken,
  upstream = { host: UPSTREAM_HOST, port: 443, secure: true },
  cacheMs = 60_000,
  rateLimitPerMin = 30,
  now = () => Date.now(),
} = {}) {
  if (!apiKey) throw new Error('COC_API_KEY is required');
  if (!proxyToken || proxyToken.length < 16) throw new Error('PROXY_TOKEN is required (at least 16 characters)');
  const cache = new Map(); // path -> {at, status, body}
  const hits = new Map(); // ip -> [timestamps]

  const limited = (ip) => {
    const t = now();
    const list = (hits.get(ip) || []).filter((x) => t - x < 60_000);
    list.push(t);
    hits.set(ip, list);
    return list.length > rateLimitPerMin;
  };

  return http.createServer((req, res) => {
    const send = (status, obj, extra = {}) => {
      const body = typeof obj === 'string' ? obj : JSON.stringify(obj);
      res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra });
      res.end(body);
    };
    if (req.method !== 'GET') return send(405, { reason: 'method', message: 'GET only' });
    if (req.url === '/health') return send(200, { ok: true });

    const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
    if (!m || !safeEqual(m[1], proxyToken)) return send(401, { reason: 'unauthorized', message: 'Bad or missing proxy token' });

    const path = req.url.split('?')[0].replace(/%23/i, '%23');
    if (!ALLOWED.some((re) => re.test(path))) return send(403, { reason: 'forbidden', message: 'Only /v1/players/{tag} is allowed' });

    const ip = req.socket.remoteAddress || 'unknown';
    if (limited(ip)) return send(429, { reason: 'throttled', message: 'Too many requests' }, { 'retry-after': '30' });

    const key = path.toUpperCase().replace('%23', '%23');
    const hit = cache.get(key);
    if (hit && now() - hit.at < cacheMs) return send(hit.status, hit.body, { 'x-cache': 'HIT' });

    const lib = upstream.secure ? https : http;
    const up = lib.request(
      { host: upstream.host, port: upstream.port, path, method: 'GET', headers: { authorization: `Bearer ${apiKey}`, accept: 'application/json' }, timeout: 10_000 },
      (r) => {
        const chunks = [];
        r.on('data', (c) => chunks.push(c));
        r.on('end', () => {
          const body = Buffer.concat(chunks).toString('utf8');
          if (r.statusCode === 200) cache.set(key, { at: now(), status: 200, body });
          if (r.statusCode === 403) return send(502, { reason: 'upstream_forbidden', message: 'Official API rejected the key. Is this server\'s public IP registered on the key?' });
          send(r.statusCode || 502, body);
        });
      },
    );
    up.on('timeout', () => up.destroy(new Error('timeout')));
    up.on('error', () => send(504, { reason: 'upstream_unreachable', message: 'Could not reach the official API' }));
    up.end();
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  if (process.argv[2] === 'ip') {
    https.get('https://api.ipify.org', (r) => { let s = ''; r.on('data', (c) => (s += c)); r.on('end', () => console.log(`Public IP of this machine: ${s.trim()}\nRegister this IP on your key at developer.clashofclans.com`)); })
      .on('error', (e) => { console.error('Could not detect IP:', e.message); process.exitCode = 1; });
  } else {
    const port = Number(process.env.PORT || 8787);
    try {
      createProxy({ apiKey: process.env.COC_API_KEY, proxyToken: process.env.PROXY_TOKEN }).listen(port, () => console.log(`ledger-proxy listening on :${port}`));
    } catch (e) { console.error(e.message); process.exit(1); }
  }
}
