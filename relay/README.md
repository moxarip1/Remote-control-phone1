# HomeVault Modular Relay Server

A lightweight, zero-dependency Node.js WebSocket-to-HTTP relay that lets your main phone access your HomeVault Home Server over mobile data or external Wi-Fi **without router port forwarding**.

## How It Works
1. Your Home Server phone opens an **outbound** WebSocket connection to `wss://your-relay-domain/ws/tunnel` and registers its `deviceId`.
2. When your main phone is outside home Wi-Fi, it calls `https://your-relay-domain/relay/<deviceId>/api/...`.
3. The Relay forwards authenticated requests (Bearer token, anti-replay nonce/timestamp, chunked uploads/downloads) over the active tunnel and streams the response back.

## Run Locally
```bash
cd relay
npm test
npm start
```

## Deploy with Docker
```bash
cd relay
docker compose up -d --build
```

Put Nginx, Caddy, or Cloudflare Tunnel in front of port `8787` for automatic HTTPS/WSS TLS termination.
