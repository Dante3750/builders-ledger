import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, statSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadConfig, saveConfig, newToken, parseTunnelUrl, looksLikeKey } from '../start.mjs';

test('config round-trips and is owner-only', () => {
  const f = path.join(mkdtempSync(path.join(os.tmpdir(), 'lp-')), 'c.json');
  assert.deepEqual(loadConfig(f), {});
  saveConfig({ apiKey: 'x', proxyToken: 'y' }, f);
  assert.equal(loadConfig(f).apiKey, 'x');
  if (process.platform !== 'win32') assert.equal(statSync(f).mode & 0o077, 0);
});
test('tokens are long and unique; tunnel url and key shape parsing', () => {
  assert.ok(newToken().length >= 16 && newToken() !== newToken());
  assert.equal(parseTunnelUrl('INF |  https://blue-fox-12.trycloudflare.com  |'), 'https://blue-fox-12.trycloudflare.com');
  assert.equal(parseTunnelUrl('no url here'), null);
  assert.ok(looksLikeKey('aaaaaaaaaa.bbbbbbbbbb.cccccccccc'));
  assert.ok(!looksLikeKey('not a key'));
});
