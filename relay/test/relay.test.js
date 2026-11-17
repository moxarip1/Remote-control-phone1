import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import crypto from 'node:crypto';
import { createRelayServer } from '../src/relayServer.js';

function createClientWsFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const mask = crypto.randomBytes(4);
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x81;
    header[1] = 0x80 | len;
  } else {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(len, 2);
  }
  const masked = Buffer.from(payload);
  for (let i = 0; i < masked.length; i++) {
    masked[i] ^= mask[i % 4];
  }
  return Buffer.concat([header, mask, masked]);
}

function connectMockHomeServerTunnel(port, deviceId, onTunnelRequest) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => {
      const key = crypto.randomBytes(16).toString('base64');
      socket.write(
        [
          'GET /ws/tunnel HTTP/1.1',
          `Host: 127.0.0.1:${port}`,
          'Upgrade: websocket',
          'Connection: Upgrade',
          `Sec-WebSocket-Key: ${key}`,
          'Sec-WebSocket-Version: 13',
          '',
          ''
        ].join('\r\n')
      );
    });

    let upgraded = false;
    let resolved = false;
    let buf = Buffer.alloc(0);

    socket.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (!upgraded) {
        const idx = buf.indexOf('\r\n\r\n');
        if (idx === -1) return;
        const head = buf.subarray(0, idx).toString('utf8');
        if (!head.includes('101 Switching Protocols')) {
          reject(new Error(`Unexpected handshake response: ${head}`));
          return;
        }
        upgraded = true;
        buf = buf.subarray(idx + 4);
        socket.write(createClientWsFrame(JSON.stringify({ type: 'register', deviceId })));
      }

      while (buf.length >= 2) {
        const len7 = buf[1] & 0x7f;
        let payloadLen = len7;
        let offset = 2;
        if (len7 === 126) {
          if (buf.length < 4) return;
          payloadLen = buf.readUInt16BE(2);
          offset = 4;
        }
        if (buf.length < offset + payloadLen) return;
        const msg = JSON.parse(buf.subarray(offset, offset + payloadLen).toString('utf8'));
        buf = buf.subarray(offset + payloadLen);

        if (msg.type === 'registered' && !resolved) {
          resolved = true;
          resolve(socket);
        } else if (msg.type === 'tunnel_request') {
          const reply = onTunnelRequest(msg);
          socket.write(createClientWsFrame(JSON.stringify(reply)));
        }
      }
    });

    socket.on('close', () => {
      if (!resolved) reject(new Error('WebSocket closed before registered'));
    });
    socket.on('error', (err) => {
      if (!resolved) reject(err);
    });
  });
}

test('Relay forwards HTTP requests from mobile client to Home Server over WebSocket tunnel', async () => {
  const { server, close } = createRelayServer({ rateLimitMax: 100 });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const { port } = server.address();

  try {
    // 1. Offline device returns 503
    const offlineResp = await fetch(`http://127.0.0.1:${port}/relay/hv-home1/api/health`, {
      headers: { Connection: 'close' }
    });
    await offlineResp.text();
    assert.equal(offlineResp.status, 503);

    // 2. Connect mock Home Server tunnel
    const tunnelSocket = await connectMockHomeServerTunnel(port, 'hv-home1', (req) => {
      assert.equal(req.path, '/api/files/list');
      assert.equal(req.query, 'path=Photos');
      assert.equal(req.headers.authorization, 'Bearer tok-123');
      const bodyJson = JSON.stringify({ currentPath: 'Photos', items: [{ name: 'beach.jpg' }] });
      return {
        type: 'tunnel_response',
        requestId: req.requestId,
        deviceId: 'hv-home1',
        statusCode: 200,
        headers: { 'Content-Type': 'application/json' },
        bodyBase64: Buffer.from(bodyJson, 'utf8').toString('base64')
      };
    });

    // 3. Health check reports 1 connected device
    const healthResp = await fetch(`http://127.0.0.1:${port}/health`, {
      headers: { Connection: 'close' }
    });
    const health = await healthResp.json();
    assert.equal(health.connectedDevices, 1);

    // 4. Client calls Home Server API via Relay
    const clientResp = await fetch(`http://127.0.0.1:${port}/relay/hv-home1/api/files/list?path=Photos`, {
      headers: { Authorization: 'Bearer tok-123', Connection: 'close' }
    });
    assert.equal(clientResp.status, 200);
    const data = await clientResp.json();
    assert.equal(data.items[0].name, 'beach.jpg');

    tunnelSocket.destroy();
  } finally {
    await close();
  }
});
