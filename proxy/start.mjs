#!/usr/bin/env node
// One-file setup + run:  node start.mjs
// Detects this machine's public IP, asks for your key ONCE, saves it (owner-only file),
// generates the proxy password, starts the proxy, opens an HTTPS tunnel if cloudflared exists,
// and prints exactly what to type into the app.
import { existsSync, readFileSync, writeFileSync, mkdirSync, chmodSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { pathToFileURL } from 'node:url';
import { createProxy } from './server.js';

export const CONFIG_FILE = path.join(os.homedir(), '.ledger-proxy.json');

export function loadConfig(file = CONFIG_FILE) {
  try { return JSON.parse(readFileSync(file, 'utf8')); } catch { return {}; }
}
export function saveConfig(cfg, file = CONFIG_FILE) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(cfg, null, 2), { mode: 0o600 });
  try { chmodSync(file, 0o600); } catch { /* windows */ }
}
export function newToken() { return randomBytes(24).toString('base64url'); }
export function parseTunnelUrl(line) { return /https:\/\/[a-z0-9-]+\.trycloudflare\.com/i.exec(line)?.[0] ?? null; }
export function looksLikeKey(s) { return /^[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}$/.test(s.trim()); }

const publicIp = () => new Promise((res) => {
  https.get('https://api.ipify.org', (r) => { let s = ''; r.on('data', (c) => (s += c)); r.on('end', () => res(s.trim() || null)); }).on('error', () => res(null));
});

function ask(question, { hidden = false } = {}) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  if (hidden) { rl._writeToOutput = (s) => { if (s.includes(question)) process.stdout.write(s); }; }
  return new Promise((res) => rl.question(question, (a) => { rl.close(); if (hidden) process.stdout.write('\n'); res(a.trim()); }));
}

async function main() {
  const cfg = loadConfig();
  const ip = await publicIp();
  console.log('\n=== ledger-proxy setup ===');
  console.log(`This machine's public IP: ${ip ?? '(could not detect; look it up at https://api.ipify.org)'}`);
  if (!cfg.apiKey) {
    console.log('\n1) Go to https://developer.clashofclans.com -> My Account -> Create New Key');
    console.log(`   Put this exact IP in "Allowed IP addresses": ${ip ?? '<your public IP>'}`);
    console.log('2) Copy the new key and paste it below (nothing is shown while you paste).\n');
    let key = '';
    while (!looksLikeKey(key)) { key = await ask('Paste key and press Enter: ', { hidden: true }); if (!looksLikeKey(key)) console.log('That does not look like a key (three parts separated by dots). Try again.'); }
    cfg.apiKey = key;
  } else console.log('\nUsing the key saved in ' + CONFIG_FILE + ' (delete that file to enter a new one).');
  if (!cfg.proxyToken) cfg.proxyToken = newToken();
  cfg.lastIp = ip;
  saveConfig(cfg);

  const port = Number(process.env.PORT || 8787);
  createProxy({ apiKey: cfg.apiKey, proxyToken: cfg.proxyToken }).listen(port, () => console.log(`\nProxy running on http://localhost:${port}`));

  let base = `http://YOUR-LAN-IP:${port}/v1 (the app needs https; see below)`;
  const tunnel = spawn('cloudflared', ['tunnel', '--no-autoupdate', '--url', `http://localhost:${port}`], { stdio: ['ignore', 'ignore', 'pipe'] });
  let printed = false;
  tunnel.on('error', () => {
    if (printed) return; printed = true;
    console.log('\nNo HTTPS tunnel yet: install cloudflared (free, no account for quick tunnels):');
    console.log('  https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/');
    console.log('then run this file again. Or put Caddy/nginx with HTTPS in front of port ' + port + '.');
    showAppValues(base, cfg.proxyToken);
  });
  tunnel.stderr.on('data', (d) => {
    const url = parseTunnelUrl(String(d));
    if (url && !printed) { printed = true; showAppValues(`${url}/v1`, cfg.proxyToken); }
  });
}

function showAppValues(base, token) {
  console.log('\n=== Put these in the app: Settings -> API ===');
  console.log(`API base URL : ${base}`);
  console.log(`API key      : ${token}      <- this is the PROXY password, not your real key`);
  console.log('Player tag   : your tag, e.g. #ABC123');
  console.log('\nKeep this window open while you use the app. The tunnel URL changes each time you restart.');
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) main();
