# ledger-proxy

A tiny forwarding proxy (Node 18+, zero dependencies) for Builder's Ledger's optional "Progress from the official API" feature.

**Why you need it:** the official API ties each key to ONE IP address, and a phone's IP keeps changing. Run this on a machine with a stable public IP and register that IP on your key. The app talks to the proxy; only the proxy talks to the official API, and only your server ever holds the real key.

What it does (and nothing more):
- Forwards `GET /v1/players/{tag}` only. Clans, wars, rankings and every other path are refused.
- The app authenticates with a separate `PROXY_TOKEN`, so the real key never reaches the phone.
- Caches good responses for 60 s and limits each client to 30 requests/min (be kind to the API).
- Turns a rejected key into a clear "is this server's IP registered?" message.

## Quick start (one file)

```bash
node start.mjs
```

It shows this machine's public IP, tells you to create a key for that IP, asks you to paste the key (hidden), saves it in `~/.ledger-proxy.json` (owner-only), generates the proxy password, starts the proxy, and, if `cloudflared` is installed, opens a free HTTPS tunnel and prints the three values to type into the app. The manual steps below explain the same thing in detail.

## Set it up (about 10 minutes)

1. **Pick a host with a stable public IP.** A small cloud VM (any provider's cheapest tier) is the reliable choice. A home PC works only while your ISP keeps your IP.
2. **Find its public IP:** on that machine run `node server.js ip`.
3. **Create a new key** at developer.clashofclans.com → My Account → Create New Key, and put that IP in *Allowed IP addresses*. Keys cannot be edited later.
4. **Start the proxy** (pick a long random PROXY_TOKEN, at least 16 characters):
   ```bash
   COC_API_KEY='paste-the-new-key' PROXY_TOKEN='make-a-long-random-string' node server.js
   ```
   Or with Docker: `docker build -t ledger-proxy . && docker run -d -p 8787:8787 -e COC_API_KEY=... -e PROXY_TOKEN=... ledger-proxy`
5. **Give it HTTPS.** The app only accepts `https://` base URLs. Easiest options: put Caddy in front (`caddy reverse-proxy --from your.domain --to localhost:8787`), or use a Cloudflare Tunnel. A tunnel only carries inbound traffic; the proxy's own outbound IP is still what the key must allow.
6. **In the app** (Settings → API): 
   - API base URL: `https://your.domain/v1`
   - API key: the **PROXY_TOKEN** (not the real key)
   - Player tag: yours, e.g. `#ABC123`
7. **Check it:** `curl -H "Authorization: Bearer $PROXY_TOKEN" https://your.domain/v1/players/%23YOURTAG`

## Tests

```bash
npm test
```

## Limits

- Never run against the live official API by its author (no key reached the build environment), only against a fake upstream.
- Single process, in-memory cache and rate limit. Fine for personal use, not for a public service.
- Keep the real key in environment variables or a secrets manager, never in the repo. Rotate it if it ever leaks.
- Respect the Supercell API terms and Fan Content Policy. Do not expose this proxy for strangers to use with your key.
