'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
let MongoClient = null;
try {
  MongoClient = require('mongodb').MongoClient;
} catch (_) {
  console.warn('[timeora] mongodb package not installed — memory mode only. Run: npm install');
}

const PORT = Number(process.env.PORT || 3000);
const MONGODB_URI = process.env.MONGODB_URI || '';
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.ADMIN_PASS || 'admin';
const SESSION_SECRET = process.env.SESSION_SECRET
  || (process.env.ADMIN_PASS ? crypto.createHash('sha256').update('timeora-session:' + process.env.ADMIN_PASS).digest('hex') : '')
  || (process.env.VERCEL ? '' : crypto.randomBytes(24).toString('hex'));
const ALLOWED_ORIGIN = process.env.ALLOWED_ORIGIN || '*';

const SEED_PATH = path.join(__dirname, 'seed-catalog.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

/** Must match app Sync.TABLES */
const SYNC_TABLES = [
  'usage_sessions', 'app_limits', 'planner_items', 'planner_done', 'app_categories', 'category_limits',
  'focus_sessions', 'habits', 'habit_logs', 'xp_transactions', 'daily_scores', 'achievement_unlocks',
  'exam_subjects', 'exam_events', 'study_logs'
];

let db = null;
let mongoClient = null;
let mongoPromise = null;
const mem = {
  catalog: null,
  events: [], // { feature, at }
  rooms: new Map(),
  devices: new Map(), // deviceId -> { deviceKey, prefs, tables: {name: {k: d}}, lastSync }
  sessions: new Map() // token -> { user, exp }
};

function loadSeedCatalog() {
  const raw = fs.readFileSync(SEED_PATH, 'utf8');
  return JSON.parse(raw);
}

async function connectMongo() {
  if (db) return;

  if (!MONGODB_URI || !MongoClient) {
    if (process.env.VERCEL) {
      throw new Error('MONGODB_URI or MongoDB driver is missing');
    }
    console.warn('[timeora] MongoDB unavailable — using in-memory store (development only).');
    mem.catalog = loadSeedCatalog();
    return;
  }

  mongoClient = new MongoClient(MONGODB_URI);
  await mongoClient.connect();
  db = mongoClient.db();

  const cat = await db.collection('catalog').findOne({ _id: 'main' });
  if (!cat) {
    const seed = loadSeedCatalog();
    await db.collection('catalog').insertOne({ _id: 'main', ...seed, updatedAt: new Date() });
    console.log('[timeora] Seeded catalog from seed-catalog.json');
  }

  console.log('[timeora] MongoDB connected');
}

function ensureMongo() {
  if (!mongoPromise) {
    mongoPromise = connectMongo().catch((err) => {
      mongoPromise = null;
      throw err;
    });
  }
  return mongoPromise;
}

async function getCatalog() {
  if (db) {
    const doc = await db.collection('catalog').findOne({ _id: 'main' });
    if (!doc) return loadSeedCatalog();
    const { _id, updatedAt, ...rest } = doc;
    return rest;
  }
  if (!mem.catalog) mem.catalog = loadSeedCatalog();
  return mem.catalog;
}

async function saveCatalog(catalog) {
  if (db) {
    await db.collection('catalog').updateOne(
      { _id: 'main' },
      { $set: { ...catalog, updatedAt: new Date() } },
      { upsert: true }
    );
  } else {
    mem.catalog = catalog;
  }
}

function scryptHash(pass, salt) {
  return crypto.scryptSync(pass, salt, 32).toString('hex');
}

function verifyAdmin(user, pass) {
  return user === ADMIN_USER && pass === ADMIN_PASS;
}

function issueSession(user) {
  if (!SESSION_SECRET) throw new Error('SESSION_SECRET is not configured');
  const payload = Buffer.from(JSON.stringify({
    user,
    exp: Date.now() + 12 * 60 * 60 * 1000
  })).toString('base64url');

  const signature = crypto
    .createHmac('sha256', SESSION_SECRET)
    .update(payload)
    .digest('base64url');

  return `${payload}.${signature}`;
}

function sessionOk(req) {
  const auth = req.headers['authorization'] || '';
  const cookie = parseCookies(req)['timeora_admin'] || '';
  const token = (auth.startsWith('Bearer ') ? auth.slice(7) : '') || cookie;
  if (!token || !SESSION_SECRET) return false;

  try {
    const parts = token.split('.');
    if (parts.length !== 2) return false;

    const expected = crypto
      .createHmac('sha256', SESSION_SECRET)
      .update(parts[0])
      .digest();

    const actual = Buffer.from(parts[1], 'base64url');
    if (actual.length !== expected.length) return false;
    if (!crypto.timingSafeEqual(actual, expected)) return false;

    const session = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
    if (!session.user || !session.exp || session.exp < Date.now()) return false;

    return session;
  } catch (_) {
    return false;
  }
}

function parseCookies(req) {
  const out = {};
  const raw = req.headers.cookie || '';
  raw.split(';').forEach((p) => {
    const i = p.indexOf('=');
    if (i > 0) out[p.slice(0, i).trim()] = decodeURIComponent(p.slice(i + 1).trim());
  });
  return out;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const buf = Buffer.concat(chunks);
      if (!buf.length) return resolve(null);
      try {
        resolve(JSON.parse(buf.toString('utf8')));
      } catch (e) {
        reject(new Error('Invalid JSON'));
      }
    });
    req.on('error', reject);
  });
}

function send(res, code, body, extraHeaders = {}) {
  const data = typeof body === 'string' ? body : JSON.stringify(body);
  const headers = {
    'Content-Type': typeof body === 'string' && body.trimStart().startsWith('<') ? 'text/html; charset=utf-8' : 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': ALLOWED_ORIGIN,
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, x-device-id, x-device-key',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
    ...extraHeaders
  };
  res.writeHead(code, headers);
  res.end(data);
}

function roomCleanup() {
  const now = Date.now();
  for (const [code, room] of mem.rooms) {
    room.members = room.members.filter((m) => now - m.lastPing < 90_000);
    if (room.members.length === 0 && now - room.createdAt > 300_000) mem.rooms.delete(code);
  }
}

function randomCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  for (let i = 0; i < 6; i++) s += chars[crypto.randomInt(chars.length)];
  return s;
}

async function deviceFromHeaders(req) {
  const deviceId = req.headers['x-device-id'];
  const deviceKey = req.headers['x-device-key'];
  if (!deviceId || !deviceKey) return null;
  if (db) {
    const d = await db.collection('devices').findOne({ deviceId, deviceKey });
    return d;
  }
  const d = mem.devices.get(deviceId);
  if (!d || d.deviceKey !== deviceKey) return null;
  return { deviceId, ...d };
}

async function handle(req, res) {
  if (req.method === 'OPTIONS') {
    return send(res, 204, '');
  }

  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;

  // Static admin assets
  if (req.method === 'GET' && (p === '/' || p === '/admin' || p === '/admin/')) {
    const html = fs.readFileSync(path.join(PUBLIC_DIR, 'admin.html'), 'utf8');
    return send(res, 200, html);
  }
  if (req.method === 'GET' && p.startsWith('/admin/')) {
    const file = path.join(PUBLIC_DIR, path.basename(p));
    if (fs.existsSync(file) && file.startsWith(PUBLIC_DIR)) {
      const ext = path.extname(file);
      const type = ext === '.js' ? 'application/javascript' : ext === '.css' ? 'text/css' : 'text/plain';
      res.writeHead(200, { 'Content-Type': type });
      return fs.createReadStream(file).pipe(res);
    }
  }

  try {
    // Health
    if (req.method === 'GET' && p === '/api/health') {
      return send(res, 200, {
        ok: true,
        version: '5.7.0',
        mongo: !!db,
        mongoConfigured: !!MONGODB_URI,
        features: (await getCatalog()).features?.length || 0
      });
    }

    // Catalog (app)
    if (req.method === 'GET' && p === '/api/catalog') {
      return send(res, 200, await getCatalog());
    }

    // Feature open events
    if (req.method === 'POST' && p === '/api/event') {
      const body = await readBody(req);
      const feature = (body && body.feature) || '';
      if (!feature) return send(res, 400, { error: 'feature required' });
      const row = { feature: String(feature).slice(0, 64), at: new Date() };
      if (db) await db.collection('events').insertOne(row);
      else {
        mem.events.push(row);
        if (mem.events.length > 5000) mem.events.shift();
      }
      return send(res, 200, { ok: true });
    }

    // Live rooms
    if (req.method === 'POST' && p === '/api/rooms') {
      roomCleanup();
      const body = await readBody(req);
      let code = randomCode();
      while (mem.rooms.has(code)) code = randomCode();
      const room = {
        code,
        type: (body && body.type) || 'focus',
        title: (body && body.title) || 'Focus room',
        createdAt: Date.now(),
        members: []
      };
      mem.rooms.set(code, room);
      if (db) await db.collection('rooms').updateOne({ code }, { $set: room }, { upsert: true });
      return send(res, 200, room);
    }
    if (req.method === 'POST' && p.startsWith('/api/rooms/') && p.endsWith('/ping')) {
      roomCleanup();
      const code = p.split('/')[3];
      const body = await readBody(req);
      let room = mem.rooms.get(code);
      if (!room && db) {
        room = await db.collection('rooms').findOne({ code });
        if (room) mem.rooms.set(code, room);
      }
      if (!room) return send(res, 404, { error: 'Room not found' });
      const deviceId = body?.deviceId || '';
      const name = (body?.name || 'Friend').slice(0, 40);
      const status = body?.status || 'focus';
      const minutes = Number(body?.minutes) || 0;
      let m = room.members.find((x) => x.deviceId === deviceId);
      if (!m) {
        m = { deviceId, name, status, minutes, lastPing: Date.now() };
        room.members.push(m);
      } else {
        m.name = name;
        m.status = status;
        m.minutes = minutes;
        m.lastPing = Date.now();
      }
      if (db) await db.collection('rooms').updateOne({ code }, { $set: room });
      return send(res, 200, room);
    }
    if (req.method === 'POST' && p.startsWith('/api/rooms/') && p.endsWith('/leave')) {
      const code = p.split('/')[3];
      const body = await readBody(req);
      const room = mem.rooms.get(code);
      if (room) {
        room.members = room.members.filter((m) => m.deviceId !== body?.deviceId);
        if (db) await db.collection('rooms').updateOne({ code }, { $set: room });
      }
      return send(res, 200, { ok: true });
    }
    if (req.method === 'GET' && p.startsWith('/api/rooms/')) {
      roomCleanup();
      const code = p.split('/')[3];
      let room = mem.rooms.get(code);
      if (!room && db) room = await db.collection('rooms').findOne({ code });
      if (!room) return send(res, 404, { error: 'Room not found' });
      return send(res, 200, room);
    }

    // Sync
    if (req.method === 'POST' && p === '/api/sync/register') {
      const body = await readBody(req);
      const deviceId = body?.deviceId;
      if (!deviceId) return send(res, 400, { error: 'deviceId required' });
      const deviceKey = crypto.randomBytes(24).toString('hex');
      const doc = { deviceId, deviceKey, createdAt: new Date(), lastSync: null, prefs: {}, tables: {}, rowCounts: {} };
      if (db) {
        await db.collection('devices').updateOne(
          { deviceId },
          { $set: { deviceKey, updatedAt: new Date() }, $setOnInsert: { createdAt: new Date(), prefs: {}, rowCounts: {} } },
          { upsert: true }
        );
      } else {
        mem.devices.set(deviceId, { deviceKey, prefs: {}, tables: {}, lastSync: null, rowCounts: {} });
      }
      return send(res, 200, { deviceKey });
    }

    if (req.method === 'POST' && p === '/api/sync/rows') {
      const device = await deviceFromHeaders(req);
      if (!device) return send(res, 401, { error: 'not registered' });
      const body = await readBody(req);
      const table = body?.table;
      const syncId = body?.syncId;
      const rows = body?.rows || [];
      if (!SYNC_TABLES.includes(table)) return send(res, 400, { error: 'unknown table' });
      if (db) {
        const col = db.collection('sync_rows');
        const ops = rows.map((r) => ({
          updateOne: {
            filter: { deviceId: device.deviceId, table, k: r.k },
            update: { $set: { d: r.d, syncId, updatedAt: new Date() } },
            upsert: true
          }
        }));
        if (ops.length) await col.bulkWrite(ops, { ordered: false });
      } else {
        const d = mem.devices.get(device.deviceId);
        if (!d.tables[table]) d.tables[table] = {};
        for (const r of rows) d.tables[table][r.k] = r.d;
      }
      return send(res, 200, { ok: true, n: rows.length });
    }

    if (req.method === 'POST' && p === '/api/sync/prefs') {
      const device = await deviceFromHeaders(req);
      if (!device) return send(res, 401, { error: 'not registered' });
      const body = await readBody(req);
      const prefs = body?.prefs || {};
      if (db) {
        await db.collection('devices').updateOne(
          { deviceId: device.deviceId },
          { $set: { prefs, updatedAt: new Date() } }
        );
      } else {
        mem.devices.get(device.deviceId).prefs = prefs;
      }
      return send(res, 200, { ok: true });
    }

    if (req.method === 'POST' && p === '/api/sync/finish') {
      const device = await deviceFromHeaders(req);
      if (!device) return send(res, 401, { error: 'not registered' });
      const body = await readBody(req);
      const syncId = body?.syncId;
      const tables = body?.tables || SYNC_TABLES;
      const rowCounts = {};
      if (db) {
        for (const t of tables) {
          // Remove rows not touched in this sync
          await db.collection('sync_rows').deleteMany({
            deviceId: device.deviceId,
            table: t,
            syncId: { $ne: syncId }
          });
          rowCounts[t] = await db.collection('sync_rows').countDocuments({ deviceId: device.deviceId, table: t });
        }
        await db.collection('devices').updateOne(
          { deviceId: device.deviceId },
          { $set: { lastSync: new Date(), rowCounts, updatedAt: new Date() } }
        );
      } else {
        const d = mem.devices.get(device.deviceId);
        d.lastSync = Date.now();
        for (const t of tables) rowCounts[t] = Object.keys(d.tables[t] || {}).length;
        d.rowCounts = rowCounts;
      }
      return send(res, 200, { ok: true, rowCounts });
    }

    if (req.method === 'DELETE' && p === '/api/sync/data') {
      const device = await deviceFromHeaders(req);
      if (!device) return send(res, 401, { error: 'not registered' });
      if (db) {
        await db.collection('sync_rows').deleteMany({ deviceId: device.deviceId });
        await db.collection('devices').deleteOne({ deviceId: device.deviceId });
      } else {
        mem.devices.delete(device.deviceId);
      }
      return send(res, 200, { ok: true });
    }

    // ---------- Admin API ----------
    if (req.method === 'POST' && p === '/api/admin/login') {
      const body = await readBody(req);
      if (!verifyAdmin(body?.user, body?.pass)) return send(res, 401, { error: 'Invalid credentials' });
      const token = issueSession(body.user);
      return send(res, 200, { token, user: body.user }, {
        'Set-Cookie': `timeora_admin=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=43200`
      });
    }

    if (req.method === 'POST' && p === '/api/admin/logout') {
      const cookie = parseCookies(req)['timeora_admin'];
      return send(res, 200, { ok: true }, {
        'Set-Cookie': 'timeora_admin=; Path=/; Max-Age=0'
      });
    }

    if (p.startsWith('/api/admin')) {
      if (!sessionOk(req)) return send(res, 401, { error: 'Unauthorized' });
    }

    if (req.method === 'GET' && p === '/api/admin/me') {
      const s = sessionOk(req);
      return send(res, 200, { user: s.user, version: '5.7.0', mongo: !!db });
    }

    if (req.method === 'GET' && p === '/api/admin/catalog') {
      return send(res, 200, await getCatalog());
    }

    if (req.method === 'PUT' && p === '/api/admin/catalog') {
      const body = await readBody(req);
      if (!body || !Array.isArray(body.features)) return send(res, 400, { error: 'features array required' });
      const catalog = {
        features: body.features,
        banners: body.banners || [],
        announcement: body.announcement || '',
        tips: body.tips || [],
        sounds: body.sounds || []
      };
      await saveCatalog(catalog);
      return send(res, 200, { ok: true, features: catalog.features.length });
    }

    if (req.method === 'POST' && p === '/api/admin/catalog/reset') {
      const seed = loadSeedCatalog();
      await saveCatalog(seed);
      return send(res, 200, { ok: true, catalog: seed });
    }

    if (req.method === 'GET' && p === '/api/admin/stats') {
      let eventAgg = [];
      let deviceCount = 0;
      let roomCount = mem.rooms.size;
      if (db) {
        eventAgg = await db.collection('events').aggregate([
          { $group: { _id: '$feature', count: { $sum: 1 } } },
          { $sort: { count: -1 } },
          { $limit: 40 }
        ]).toArray();
        deviceCount = await db.collection('devices').countDocuments();
        roomCount = await db.collection('rooms').countDocuments();
      } else {
        const map = {};
        for (const e of mem.events) map[e.feature] = (map[e.feature] || 0) + 1;
        eventAgg = Object.entries(map).map(([id, count]) => ({ _id: id, count })).sort((a, b) => b.count - a.count);
        deviceCount = mem.devices.size;
      }
      const cat = await getCatalog();
      return send(res, 200, {
        events: eventAgg,
        devices: deviceCount,
        rooms: roomCount,
        featuresEnabled: (cat.features || []).filter((f) => f.enabled !== false).length,
        featuresTotal: (cat.features || []).length,
        syncTables: SYNC_TABLES
      });
    }

    if (req.method === 'GET' && p === '/api/admin/devices') {
      let list = [];
      if (db) {
        list = await db.collection('devices')
          .find({}, { projection: { deviceKey: 0 } })
          .sort({ lastSync: -1 })
          .limit(100)
          .toArray();
      } else {
        list = [...mem.devices.entries()].map(([deviceId, d]) => ({
          deviceId,
          lastSync: d.lastSync,
          rowCounts: d.rowCounts || {},
          prefsKeys: Object.keys(d.prefs || {}).length
        }));
      }
      return send(res, 200, { devices: list, note: 'Opt-in backup only. Raw row payloads not shown in admin for privacy.' });
    }

    if (req.method === 'DELETE' && p.startsWith('/api/admin/devices/')) {
      const deviceId = decodeURIComponent(p.slice('/api/admin/devices/'.length));
      if (db) {
        await db.collection('sync_rows').deleteMany({ deviceId });
        await db.collection('devices').deleteOne({ deviceId });
      } else {
        mem.devices.delete(deviceId);
      }
      return send(res, 200, { ok: true });
    }

    if (req.method === 'GET' && p === '/api/admin/meta') {
      return send(res, 200, {
        appVersionAlign: '5.7.0',
        manageable: [
          'Feature catalog (title, tagline, why, route, order, enabled, color)',
          'Home banners',
          'Announcement bar',
          'Daily tips',
          'Cloud ambient sounds (https MP3)',
          'Feature open analytics (anonymous)',
          'Opt-in sync device list + wipe (MongoDB)',
          'Live rooms count'
        ],
        notManagedInAdmin: [
          'In-app UX (quiet home, Dump undo, Focus ritual) — ship in app builds',
          '25 Time-Saving tool implementations — app code',
          'Local screen-time / usage without user opt-in sync',
          'Festival themes (app ignores; not exposed)'
        ],
        syncTables: SYNC_TABLES,
        mongo: !!db
      });
    }

    return send(res, 404, { error: 'Not found' });
  } catch (e) {
    console.error(e);
    return send(res, 500, { error: e.message || 'Server error' });
  }
}

function missingVercelEnv() {
  const missing = [];
  if (!MONGODB_URI) missing.push('MONGODB_URI');
  if (!process.env.ADMIN_USER) missing.push('ADMIN_USER');
  if (!process.env.ADMIN_PASS) missing.push('ADMIN_PASS');
  // SESSION_SECRET is optional when ADMIN_PASS is set (derived above)
  if (!SESSION_SECRET) missing.push('SESSION_SECRET (or set ADMIN_PASS so one can be derived)');
  return missing;
}

async function handler(req, res) {
  const urlPath = String((req.url || '').split('?')[0] || '').replace(/\/+$/, '') || '/';
  const isHealth = urlPath === '/api/health' || urlPath === '/health';

  try {
    if (process.env.VERCEL) {
      const missing = missingVercelEnv();
      if (missing.length) {
        const detail = 'Missing Vercel env: ' + missing.join(', ') +
          '. Project → Settings → Environment Variables → add them for Production → Redeploy.';
        console.error('[timeora] ' + detail);
        if (!res.headersSent) {
          send(res, 500, {
            error: 'Server configuration or database error',
            detail,
            missing,
            ok: false
          });
        }
        return;
      }
    }

    try {
      await ensureMongo();
    } catch (mongoErr) {
      const detail = 'MongoDB connection failed: ' + (mongoErr && mongoErr.message ? mongoErr.message : String(mongoErr)) +
        '. Check MONGODB_URI, Atlas user/password (URL-encode special chars), Network Access 0.0.0.0/0, cluster not paused.';
      console.error('[timeora] ' + detail);
      if (isHealth && !res.headersSent) {
        send(res, 503, {
          ok: false,
          error: 'Server configuration or database error',
          detail,
          mongoConfigured: !!MONGODB_URI
        });
        return;
      }
      if (!res.headersSent) {
        send(res, 500, { error: 'Server configuration or database error', detail });
      }
      return;
    }

    await handle(req, res);
  } catch (e) {
    console.error(e);
    if (!res.headersSent) {
      send(res, 500, {
        error: 'Server configuration or database error',
        detail: e.message || 'Server error'
      });
    }
  }
}

module.exports = handler;

async function main() {
  await ensureMongo();
  const server = http.createServer(handler);
  server.listen(PORT, () => {
    console.log(`[timeora] v5.7.0 listening on :${PORT}`);
    console.log(`[timeora] Admin UI  http://localhost:${PORT}/admin`);
    console.log(`[timeora] Catalog   http://localhost:${PORT}/api/catalog`);
  });
}

if (!process.env.VERCEL) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
