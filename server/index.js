// Relay server giữa iPhone (trang điều khiển / Phím tắt iOS) và app trên Fire TV.
//
// - TV kết nối WebSocket:     /ws?role=tv&code=ABC123
// - iPhone kết nối WebSocket: /ws?role=remote&code=ABC123
// - Phím tắt iOS gọi HTTP:    POST /api/cast {code, url}
//
// Lệnh từ iPhone được chuyển nguyên vẹn sang TV; trạng thái phát từ TV được
// gửi ngược lại cho mọi iPhone đang mở trang điều khiển của mã đó.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');
const CODE_RE = /^[A-Z0-9]{6}$/;
const COMMANDS = new Set([
  'play', 'pause', 'resume', 'toggle', 'seek', 'seekBy', 'stop', 'volume',
  'fullscreen', 'back', 'reload', 'home', 'move', 'click', 'scroll',
]);

// code -> { tv: WebSocket|null, remotes: Set<WebSocket>, status: object|null }
const rooms = new Map();

function room(code) {
  let r = rooms.get(code);
  if (!r) {
    r = { tv: null, remotes: new Set(), status: null };
    rooms.set(code, r);
  }
  return r;
}

function cleanupRoom(code) {
  const r = rooms.get(code);
  if (r && !r.tv && r.remotes.size === 0) rooms.delete(code);
}

function send(ws, msg) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function broadcastRemotes(r, msg) {
  for (const ws of r.remotes) send(ws, msg);
}

function normalizeCode(code) {
  const c = String(code || '').trim().toUpperCase();
  return CODE_RE.test(c) ? c : null;
}

function isHttpUrl(u) {
  try {
    const p = new URL(u);
    return p.protocol === 'http:' || p.protocol === 'https:';
  } catch {
    return false;
  }
}

// Kiểm tra và làm sạch lệnh trước khi chuyển sang TV. Trả về lệnh hợp lệ hoặc chuỗi lỗi.
function sanitizeCommand(cmd) {
  if (!cmd || typeof cmd !== 'object' || !COMMANDS.has(cmd.type)) return 'Lệnh không hợp lệ';
  switch (cmd.type) {
    case 'play': {
      const url = String(cmd.url || '').trim();
      if (!isHttpUrl(url)) return 'Link phải bắt đầu bằng http:// hoặc https://';
      const out = { type: 'play', url };
      if (cmd.title) out.title = String(cmd.title).slice(0, 200);
      if (cmd.referer && isHttpUrl(cmd.referer)) out.referer = String(cmd.referer);
      return out;
    }
    case 'seek': {
      const position = Number(cmd.position);
      return Number.isFinite(position) && position >= 0 ? { type: 'seek', position } : 'Vị trí tua không hợp lệ';
    }
    case 'seekBy': {
      const delta = Number(cmd.delta);
      return Number.isFinite(delta) ? { type: 'seekBy', delta } : 'Bước tua không hợp lệ';
    }
    case 'volume':
      return cmd.dir === 'up' || cmd.dir === 'down' ? { type: 'volume', dir: cmd.dir } : 'Hướng âm lượng không hợp lệ';
    case 'move':
    case 'scroll': {
      const clamp = (n) => Math.max(-2000, Math.min(2000, Number(n) || 0));
      return { type: cmd.type, dx: clamp(cmd.dx), dy: clamp(cmd.dy) };
    }
    default:
      return { type: cmd.type };
  }
}

// Gửi lệnh tới TV. Trả về null nếu thành công, hoặc thông báo lỗi.
function dispatch(code, rawCmd) {
  const cmd = sanitizeCommand(rawCmd);
  if (typeof cmd === 'string') return { status: 400, error: cmd };
  const r = rooms.get(code);
  if (!r || !r.tv || r.tv.readyState !== r.tv.OPEN) {
    return { status: 404, error: 'TV chưa kết nối. Hãy mở app TV Smart Connect trên Fire TV.' };
  }
  send(r.tv, cmd);
  return null;
}

// ---------- HTTP ----------

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.apk': 'application/vnd.android.package-archive',
};

function json(res, status, body) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 64 * 1024) req.destroy();
    });
    req.on('end', () => {
      if (!data) return resolve({});
      try {
        resolve(JSON.parse(data));
      } catch {
        // Phím tắt iOS đôi khi gửi form-urlencoded
        resolve(Object.fromEntries(new URLSearchParams(data)));
      }
    });
    req.on('error', () => resolve({}));
  });
}

function serveStatic(req, res, pathname) {
  let rel = pathname === '/' ? '/index.html' : pathname;
  if (rel === '/tv.apk') rel = '/tv-smart-connect.apk';
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403).end();
    return;
  }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Không tìm thấy');
      return;
    }
    const ext = path.extname(file);
    const headers = { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Content-Length': st.size };
    if (ext === '.apk') headers['Content-Disposition'] = 'attachment; filename="tv-smart-connect.apk"';
    if (ext === '.html') headers['Cache-Control'] = 'no-cache';
    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const { pathname } = url;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    });
    return res.end();
  }

  if (pathname === '/healthz') return json(res, 200, { ok: true });

  // Phím tắt iOS: POST /api/cast {code, url}  (hoặc GET /api/cast?code=..&url=..)
  if (pathname === '/api/cast' || pathname === '/api/cmd') {
    const body = req.method === 'POST' ? await readBody(req) : Object.fromEntries(url.searchParams);
    const code = normalizeCode(body.code);
    if (!code) return json(res, 400, { ok: false, error: 'Mã TV không hợp lệ' });
    const cmd = pathname === '/api/cast' ? { ...body, type: 'play' } : body.cmd || body;
    const err = dispatch(code, cmd);
    if (err) return json(res, err.status, { ok: false, error: err.error });
    return json(res, 200, { ok: true, message: 'Đã gửi lên TV' });
  }

  if (pathname === '/api/status') {
    const code = normalizeCode(url.searchParams.get('code'));
    if (!code) return json(res, 400, { ok: false, error: 'Mã TV không hợp lệ' });
    const r = rooms.get(code);
    return json(res, 200, { ok: true, online: !!(r && r.tv), status: r ? r.status : null });
  }

  if (req.method === 'GET' || req.method === 'HEAD') return serveStatic(req, res, pathname);
  res.writeHead(405).end();
});

// ---------- WebSocket ----------

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });

wss.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const role = params.get('role');
  const code = normalizeCode(params.get('code'));
  if (!code || (role !== 'tv' && role !== 'remote')) {
    ws.close(4000, 'bad params');
    return;
  }

  ws.isAlive = true;
  ws.on('pong', () => (ws.isAlive = true));
  const r = room(code);

  if (role === 'tv') {
    // Cùng một TV kết nối lại (kết nối cũ có thể đã chết) -> thay thế kết nối cũ.
    if (r.tv && r.tv !== ws) r.tv.terminate();
    r.tv = ws;
    broadcastRemotes(r, { type: 'tv', online: true });

    ws.on('message', (data) => {
      ws.isAlive = true;
      let msg;
      try {
        msg = JSON.parse(data);
      } catch {
        return;
      }
      if (msg.type === 'ping') return send(ws, { type: 'pong' });
      if (msg.type === 'status') {
        r.status = msg;
        broadcastRemotes(r, msg);
      } else if (msg.type === 'error' || msg.type === 'notice') {
        broadcastRemotes(r, msg);
      }
    });

    ws.on('close', () => {
      if (r.tv !== ws) return;
      r.tv = null;
      r.status = null;
      broadcastRemotes(r, { type: 'tv', online: false });
      cleanupRoom(code);
    });
  } else {
    r.remotes.add(ws);
    send(ws, { type: 'tv', online: !!r.tv });
    if (r.status) send(ws, r.status);

    ws.on('message', (data) => {
      ws.isAlive = true;
      let msg;
      try {
        msg = JSON.parse(data);
      } catch {
        return;
      }
      if (msg.type === 'ping') return send(ws, { type: 'pong' });
      const err = dispatch(code, msg);
      // Lệnh di chuột gửi liên tục, không cần báo lỗi từng cái.
      if (err && msg.type !== 'move' && msg.type !== 'scroll') send(ws, { type: 'error', message: err.error });
    });

    ws.on('close', () => {
      r.remotes.delete(ws);
      cleanupRoom(code);
    });
  }
});

// Ping định kỳ để giữ kết nối qua proxy và dọn kết nối chết.
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 25000);
wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, () => console.log(`TV Smart Connect server listening on :${PORT}`));
