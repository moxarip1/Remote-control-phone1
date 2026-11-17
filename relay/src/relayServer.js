import http from 'node:http';
import crypto from 'node:crypto';
import { URL } from 'node:url';

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/**
 * Minimal RFC 6455 WebSocket frame encoder/decoder using built-in Node.js primitives.
 */
function encodeWsTextFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x81; // FIN + text frame
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

function createWsReader(onMessage, onClose, onPing) {
  let buffer = Buffer.alloc(0);
  let fragmentedChunks = [];

  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 2) {
      const b0 = buffer[0];
      const b1 = buffer[1];
      const fin = (b0 & 0x80) !== 0;
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let payloadLen = b1 & 0x7f;
      let offset = 2;

      if (payloadLen === 126) {
        if (buffer.length < 4) return;
        payloadLen = buffer.readUInt16BE(2);
        offset = 4;
      } else if (payloadLen === 127) {
        if (buffer.length < 10) return;
        payloadLen = Number(buffer.readBigUInt64BE(2));
        offset = 10;
      }

      const maskLen = masked ? 4 : 0;
      if (buffer.length < offset + maskLen + payloadLen) return;

      const maskKey = masked ? buffer.subarray(offset, offset + 4) : null;
      offset += maskLen;

      const payload = Buffer.from(buffer.subarray(offset, offset + payloadLen));
      buffer = buffer.subarray(offset + payloadLen);

      if (masked && maskKey) {
        for (let i = 0; i < payload.length; i++) {
          payload[i] ^= maskKey[i % 4];
        }
      }

      if (opcode === 0x8) {
        onClose();
        return;
      }
      if (opcode === 0x9) {
        if (onPing) onPing(payload);
        continue;
      }
      if (opcode === 0xa) {
        continue;
      }
      if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) {
        fragmentedChunks.push(payload);
        if (fin) {
          const full = Buffer.concat(fragmentedChunks);
          fragmentedChunks = [];
          onMessage(full.toString('utf8'));
        }
      }
    }
  };
}

export function createRelayServer(options = {}) {
  const maxBodyBytes = options.maxBodyBytes ?? 20 * 1024 * 1024;
  const requestTimeoutMs = options.requestTimeoutMs ?? 45_000;
  const rateLimitMax = options.rateLimitMax ?? 180;
  const rateLimitWindowMs = options.rateLimitWindowMs ?? 60_000;
  const relaySecret = options.relaySecret ?? process.env.RELAY_SHARED_SECRET ?? '';

  /** @type {Map<string, { socket: import('node:net').Socket, deviceId: string, connectedAt: number }>} */
  const tunnels = new Map();
  /** @type {Map<string, { resolve: Function, reject: Function, timer: NodeJS.Timeout }>} */
  const pendingRequests = new Map();
  /** @type {Map<string, number[]>} */
  const ipHits = new Map();

  function checkRateLimit(ip) {
    const now = Date.now();
    const hits = (ipHits.get(ip) || []).filter((t) => now - t < rateLimitWindowMs);
    if (hits.length >= rateLimitMax) {
      ipHits.set(ip, hits);
      return false;
    }
    hits.push(now);
    ipHits.set(ip, hits);
    return true;
  }

  const server = http.createServer((req, res) => {
    const clientIp = req.socket.remoteAddress || 'unknown';
    if (!checkRateLimit(clientIp)) {
      res.writeHead(429, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code: 'RATE_LIMITED', message: 'Too many requests.' }));
      return;
    }

    const parsedUrl = new URL(req.url || '/', 'http://localhost');
    if (req.method === 'GET' && parsedUrl.pathname === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          status: 'ok',
          connectedDevices: tunnels.size,
          uptimeSeconds: Math.floor(process.uptime())
        })
      );
      return;
    }

    const match = parsedUrl.pathname.match(/^\/relay\/([^/]+)(\/.*)?$/);
    if (!match) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code: 'NOT_FOUND', message: 'Route not found.' }));
      return;
    }

    const deviceId = decodeURIComponent(match[1]);
    const forwardedPath = match[2] || '/';
    const tunnel = tunnels.get(deviceId);
    if (!tunnel || tunnel.socket.destroyed) {
      res.writeHead(503, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          code: 'DEVICE_OFFLINE',
          message: 'Home Storage server is currently offline on the relay.'
        })
      );
      return;
    }

    const chunks = [];
    let totalLen = 0;
    let aborted = false;

    req.on('data', (chunk) => {
      totalLen += chunk.length;
      if (totalLen > maxBodyBytes) {
        aborted = true;
        res.writeHead(413, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ code: 'PAYLOAD_TOO_LARGE', message: 'Chunk exceeds relay limit.' }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      if (aborted) return;
      const bodyBuffer = Buffer.concat(chunks);
      const requestId = crypto.randomUUID();
      const forwardHeaders = {};
      for (const [k, v] of Object.entries(req.headers)) {
        if (typeof v === 'string' && k !== 'host' && k !== 'connection' && k !== 'content-length') {
          forwardHeaders[k] = v;
        }
      }

      const envelope = {
        type: 'tunnel_request',
        requestId,
        deviceId,
        method: req.method || 'GET',
        path: forwardedPath,
        query: parsedUrl.search ? parsedUrl.search.slice(1) : '',
        headers: forwardHeaders,
        bodyBase64: bodyBuffer.length > 0 ? bodyBuffer.toString('base64') : ''
      };

      const timer = setTimeout(() => {
        pendingRequests.delete(requestId);
        if (!res.headersSent) {
          res.writeHead(504, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ code: 'GATEWAY_TIMEOUT', message: 'Home Server did not respond in time.' }));
        }
      }, requestTimeoutMs);

      pendingRequests.set(requestId, {
        timer,
        resolve: (reply) => {
          clearTimeout(timer);
          const respBuf = reply.bodyBase64 ? Buffer.from(reply.bodyBase64, 'base64') : Buffer.alloc(0);
          const outHeaders = { ...(reply.headers || {}), 'Content-Length': String(respBuf.length) };
          res.writeHead(reply.statusCode || 200, outHeaders);
          res.end(respBuf);
        },
        reject: () => {
          clearTimeout(timer);
          if (!res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ code: 'BAD_GATEWAY', message: 'Tunnel disconnected.' }));
          }
        }
      });

      tunnel.socket.write(encodeWsTextFrame(JSON.stringify(envelope)));
    });
  });

  server.on('upgrade', (req, socket) => {
    const parsedUrl = new URL(req.url || '/', 'http://localhost');
    if (parsedUrl.pathname !== '/ws/tunnel') {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }

    if (relaySecret) {
      const provided = req.headers['x-relay-secret'];
      if (provided !== relaySecret) {
        socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
        socket.destroy();
        return;
      }
    }

    const wsKey = req.headers['sec-websocket-key'];
    if (!wsKey || typeof wsKey !== 'string') {
      socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
      socket.destroy();
      return;
    }

    const acceptKey = crypto
      .createHash('sha1')
      .update(wsKey + WS_GUID)
      .digest('base64');

    socket.write(
      [
        'HTTP/1.1 101 Switching Protocols',
        'Upgrade: websocket',
        'Connection: Upgrade',
        `Sec-WebSocket-Accept: ${acceptKey}`,
        '',
        ''
      ].join('\r\n')
    );

    let registeredDeviceId = null;

    const cleanupTunnel = () => {
      if (registeredDeviceId && tunnels.get(registeredDeviceId)?.socket === socket) {
        tunnels.delete(registeredDeviceId);
      }
    };

    const reader = createWsReader(
      (rawText) => {
        let msg;
        try {
          msg = JSON.parse(rawText);
        } catch {
          return;
        }
        if (msg.type === 'register' && typeof msg.deviceId === 'string' && msg.deviceId.length > 0) {
          registeredDeviceId = msg.deviceId;
          const prev = tunnels.get(registeredDeviceId);
          if (prev && prev.socket !== socket) {
            prev.socket.destroy();
          }
          tunnels.set(registeredDeviceId, {
            socket,
            deviceId: registeredDeviceId,
            connectedAt: Date.now()
          });
          socket.write(
            encodeWsTextFrame(JSON.stringify({ type: 'registered', deviceId: registeredDeviceId }))
          );
        } else if (msg.type === 'tunnel_response' && msg.requestId) {
          const pending = pendingRequests.get(msg.requestId);
          if (pending) {
            pendingRequests.delete(msg.requestId);
            pending.resolve(msg);
          }
        } else if (msg.type === 'ping') {
          socket.write(encodeWsTextFrame(JSON.stringify({ type: 'pong' })));
        }
      },
      () => {
        cleanupTunnel();
        socket.destroy();
      },
      (pingPayload) => {
        const len = Math.min(pingPayload.length, 125);
        socket.write(Buffer.concat([Buffer.from([0x8a, len]), pingPayload.subarray(0, len)]));
      }
    );

    socket.on('data', reader);
    socket.on('end', () => {
      cleanupTunnel();
      socket.destroy();
    });
    socket.on('close', cleanupTunnel);
    socket.on('error', cleanupTunnel);
  });

  function close() {
    for (const t of tunnels.values()) {
      t.socket.destroy();
    }
    tunnels.clear();
    for (const p of pendingRequests.values()) {
      clearTimeout(p.timer);
      p.reject();
    }
    pendingRequests.clear();
    server.closeAllConnections?.();
    return new Promise((resolve) => server.close(() => resolve()));
  }

  return { server, tunnels, encodeWsTextFrame, close };
}
