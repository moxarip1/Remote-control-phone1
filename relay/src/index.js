import { createRelayServer } from './relayServer.js';

const port = Number(process.env.PORT || 8787);
const host = process.env.HOST || '0.0.0.0';

const { server } = createRelayServer({
  maxBodyBytes: Number(process.env.RELAY_MAX_BODY_BYTES || 20 * 1024 * 1024),
  requestTimeoutMs: Number(process.env.RELAY_REQUEST_TIMEOUT_MS || 45_000),
  rateLimitMax: Number(process.env.RELAY_RATE_LIMIT_MAX || 180),
  relaySecret: process.env.RELAY_SHARED_SECRET || ''
});

server.listen(port, host, () => {
  console.log(`[HomeVault Relay] Listening on http://${host}:${port}`);
});

function shutdown() {
  console.log('[HomeVault Relay] Shutting down gracefully...');
  server.close(() => process.exit(0));
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
