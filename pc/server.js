'use strict';
/*
 * Cúmulo Hoops – servidor para PC (alternativa a la app Android).
 * Sin dependencias: solo Node.js (v18 o superior). Usa las mismas pantallas de ../web.
 * La lógica de referencia es la de Kotlin (app/src/main/java/.../core/Game.kt):
 * si se cambia una regla del partido, hay que cambiarla en los dos lados.
 *
 *   node server.js            -> inicia en el puerto 3000
 *   PORT=8080 node server.js  -> otro puerto
 *
 * Pantallas:
 *   /operador  mesa de control (notebook, tablet o celular)
 *   /tablero   tablero principal (TV grande)
 *   /posesion  reloj de 24 segundos (TVs chicas)
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.env.PORT) || 3000;
const PUBLIC_DIR = path.join(__dirname, '..', 'web');
const STATE_FILE = path.join(__dirname, 'partido.json');

// ------------------------------------------------------------------ estado
let nextId = 1;

function samplePlayers() {
  const list = [];
  for (let n = 4; n <= 15; n++) {
    list.push({ id: nextId++, num: String(n), name: 'Jugador ' + n, onCourt: n <= 8, points: 0, fouls: 0 });
  }
  return list;
}

function makeTeam(name, short, color) {
  return { name, short, color, score: 0, fouls: 0, timeouts: 0, players: samplePlayers() };
}

function defaultState() {
  return {
    title: 'U13 Tira Blanca',
    period: 1,
    periods: 4,
    periodLength: 8 * 60000,
    otLength: 5 * 60000,
    clock: { running: false, remaining: 8 * 60000, startedAt: 0 },
    shot: { running: false, remaining: 24000, startedAt: 0 },
    possession: null,
    home: makeTeam('Club Atlético Echesortu', 'CAE', '#ef4444'),
    away: makeTeam('Club Sorrento', 'SOR', '#38bdf8'),
    buzzer: 0,
    log: []
  };
}

let state = defaultState();
loadState();

// ------------------------------------------------------------------ relojes
// Cada reloj guarda "remaining" (ms) y, si corre, el instante en que arrancó.
// Las pantallas calculan el tiempo localmente, así no hace falta mandar ticks.
const now = () => Date.now();
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
function rem(c) { return c.running ? Math.max(0, c.remaining - (now() - c.startedAt)) : c.remaining; }
function pauseC(c) { c.remaining = rem(c); c.running = false; }
function startC(c) { if (c.remaining > 0) { c.startedAt = now(); c.running = true; } }
function setC(c, ms) { c.remaining = Math.max(0, Math.round(ms)); c.startedAt = now(); }
function curLength() { return state.period <= state.periods ? state.periodLength : state.otLength; }
function periodLabel() { return state.period <= state.periods ? 'Q' + state.period : 'OT' + (state.period - state.periods); }
function fmtClock(ms) { const s = Math.ceil(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

function addLog(text) {
  state.log.unshift({ p: periodLabel(), c: fmtClock(rem(state.clock)), text });
  if (state.log.length > 300) state.log.length = 300;
}

function mustTeam(k) {
  if (k !== 'home' && k !== 'away') throw new Error('Equipo inválido');
  return state[k];
}
function findPlayer(t, id) { return id == null ? null : t.players.find(p => p.id === Number(id)) || null; }

function parseRoster(text, old) {
  const players = [];
  for (const raw of String(text).split(/\r?\n/)) {
    const m = raw.trim().match(/^#?(\d{1,3})\s*[-.)]?\s*(.*)$/);
    if (!m) continue;
    const num = m[1];
    const name = (m[2] || '').trim().slice(0, 40) || 'Jugador ' + num;
    const prev = old.find(p => p.num === num);
    players.push(prev ? Object.assign(prev, { name }) : { id: nextId++, num, name, onCourt: false, points: 0, fouls: 0 });
    if (players.length >= 20) break;
  }
  return players;
}

// ------------------------------------------------------------------ deshacer
// Se deshacen puntos, faltas, tiempos, cambios y posesión (no los relojes).
const UNDOABLE = new Set(['score', 'foul', 'timeout', 'sub', 'possession', 'swapPossession']);
const undoStack = [];
function snapshot() {
  return JSON.stringify({ home: state.home, away: state.away, possession: state.possession, log: state.log });
}

// ------------------------------------------------------------------ acciones
const actions = {
  clockToggle() {
    if (state.clock.running) { pauseC(state.clock); pauseC(state.shot); return; }
    if (rem(state.clock) <= 0) return;
    startC(state.clock);
    if (state.shot.remaining > 0) startC(state.shot);
  },
  clockAdjust({ ms }) { setC(state.clock, clamp(rem(state.clock) + Number(ms || 0), 0, curLength())); },
  clockSet({ ms }) { setC(state.clock, clamp(Number(ms) || 0, 0, 60 * 60000)); },

  shotToggle() {
    if (state.shot.running) pauseC(state.shot);
    else startC(state.shot);
  },
  shotReset({ s }) {
    const sec = Number(s) === 14 ? 14 : 24;
    setC(state.shot, sec * 1000);
    if (state.clock.running) state.shot.running = true; else state.shot.running = false;
  },
  shotAdjust({ ms }) { setC(state.shot, clamp(rem(state.shot) + Number(ms || 0), 0, 24000)); },

  period({ n }) {
    n = clamp(Math.round(Number(n) || 1), 1, 12);
    pauseC(state.clock); pauseC(state.shot);
    state.period = n;
    setC(state.clock, curLength());
    setC(state.shot, 24000);
    // En FIBA las faltas de equipo se reinician cada cuarto; el tiempo extra continúa el 4º.
    if (n <= state.periods) { state.home.fouls = 0; state.away.fouls = 0; }
    addLog('Inicio de ' + periodLabel());
  },

  score({ team, pts, playerId }) {
    const t = mustTeam(team);
    pts = Number(pts) || 0;
    if (!pts || t.score + pts < 0) return;
    const p = findPlayer(t, playerId);
    t.score += pts;
    if (p) p.points = Math.max(0, p.points + pts);
    addLog(`${t.short} ${pts > 0 ? '+' + pts : pts}${p ? ' · #' + p.num + ' ' + p.name : ''}  (${state.home.score}-${state.away.score})`);
  },

  foul({ team, playerId, delta }) {
    const t = mustTeam(team);
    delta = Number(delta) < 0 ? -1 : 1;
    const p = findPlayer(t, playerId);
    t.fouls = Math.max(0, t.fouls + delta);
    if (p) p.fouls = Math.max(0, p.fouls + delta);
    if (delta > 0) {
      addLog(`Falta ${t.short}${p ? ' · #' + p.num + ' ' + p.name + ' (' + p.fouls + ')' : ' (equipo)'}${p && p.fouls >= 5 ? ' — 5 FALTAS' : ''}`);
    } else {
      addLog(`Corrección: −1 falta ${t.short}`);
    }
  },

  timeout({ team, delta }) {
    const t = mustTeam(team);
    delta = Number(delta) < 0 ? -1 : 1;
    t.timeouts = Math.max(0, t.timeouts + delta);
    if (delta > 0) {
      pauseC(state.clock); pauseC(state.shot);
      addLog(`Tiempo muerto ${t.short} (${t.timeouts})`);
    } else {
      addLog(`Corrección: −1 tiempo muerto ${t.short}`);
    }
  },

  sub({ team, playerId }) {
    const t = mustTeam(team);
    const p = findPlayer(t, playerId);
    if (!p) return;
    p.onCourt = !p.onCourt;
    addLog(`${p.onCourt ? 'Ingresa' : 'Sale'} ${t.short} · #${p.num} ${p.name}`);
  },

  possession({ team }) {
    state.possession = team === 'home' || team === 'away' ? team : null;
  },
  swapPossession() {
    state.possession = state.possession === 'home' ? 'away' : 'home';
    actions.shotReset({ s: 24 });
    addLog('Cambio de posesión → ' + state[state.possession].short);
  },

  buzzer() { state.buzzer++; },

  config(m) {
    if (typeof m.title === 'string') state.title = m.title.trim().slice(0, 60);
    const fullBefore = !state.clock.running && state.clock.remaining === curLength();
    const pm = Number(m.periodMinutes);
    if (pm > 0 && pm <= 30) state.periodLength = Math.round(pm * 60000);
    const om = Number(m.otMinutes);
    if (om > 0 && om <= 15) state.otLength = Math.round(om * 60000);
    if (fullBefore) setC(state.clock, curLength());
    for (const side of ['home', 'away']) {
      const c = m[side];
      if (!c) continue;
      const t = state[side];
      if (c.name && String(c.name).trim()) t.name = String(c.name).trim().slice(0, 60);
      if (c.short && String(c.short).trim()) t.short = String(c.short).trim().slice(0, 4).toUpperCase();
      if (/^#[0-9a-f]{6}$/i.test(c.color || '')) t.color = c.color;
      if (typeof c.roster === 'string') t.players = parseRoster(c.roster, t.players);
    }
    addLog('Configuración actualizada');
  },

  newGame() {
    for (const side of ['home', 'away']) {
      const t = state[side];
      t.score = 0; t.fouls = 0; t.timeouts = 0;
      t.players.forEach(p => { p.points = 0; p.fouls = 0; });
    }
    state.period = 1;
    pauseC(state.clock); pauseC(state.shot);
    setC(state.clock, state.periodLength);
    setC(state.shot, 24000);
    state.possession = null;
    state.log = [];
    undoStack.length = 0;
    addLog('Nuevo partido');
  },

  undo() {
    const snap = undoStack.pop();
    if (!snap) return;
    Object.assign(state, JSON.parse(snap));
  }
};

function dispatch(msg) {
  const fn = actions[msg && msg.type];
  if (!fn) throw new Error('Acción desconocida: ' + (msg && msg.type));
  if (UNDOABLE.has(msg.type)) {
    undoStack.push(snapshot());
    if (undoStack.length > 200) undoStack.shift();
  }
  fn(msg);
  broadcast();
  scheduleSave();
}

// ------------------------------------------------------------------ vencimiento de relojes
let lastPeriodicSave = 0;
setInterval(() => {
  let changed = false;
  if (state.clock.running && rem(state.clock) <= 0) {
    pauseC(state.clock); pauseC(state.shot);
    state.buzzer++;
    addLog('Fin de ' + periodLabel());
    changed = true;
  }
  if (state.shot.running && rem(state.shot) <= 0) {
    pauseC(state.shot); pauseC(state.clock);
    state.buzzer++;
    addLog('Violación de reloj de tiro');
    changed = true;
  }
  if (changed) { broadcast(); scheduleSave(); }
  if (state.clock.running && now() - lastPeriodicSave > 5000) { lastPeriodicSave = now(); scheduleSave(); }
}, 25);

// ------------------------------------------------------------------ guardado en disco
let saveTimer = null;
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    const copy = Object.assign({}, state, {
      clock: { running: false, remaining: rem(state.clock), startedAt: 0 },
      shot: { running: false, remaining: rem(state.shot), startedAt: 0 }
    });
    fs.writeFile(STATE_FILE, JSON.stringify(copy, null, 1), () => {});
  }, 400);
}

function loadState() {
  try {
    const saved = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    state = Object.assign(defaultState(), saved);
    state.clock.running = false;
    state.shot.running = false;
    let max = 0;
    for (const k of ['home', 'away']) for (const p of state[k].players) max = Math.max(max, p.id);
    nextId = max + 1;
    console.log('  Partido anterior recuperado de partido.json');
  } catch (e) { /* no había partido guardado */ }
}

// ------------------------------------------------------------------ tiempo real (Server-Sent Events)
const clients = new Set();
function payload() { return JSON.stringify(Object.assign({}, state, { now: now() })); }
function broadcast() {
  const msg = 'data: ' + payload() + '\n\n';
  for (const res of clients) res.write(msg);
}
// Reenvío periódico: resincroniza los relojes de las TVs y detecta desconexiones.
setInterval(broadcast, 5000);

// ------------------------------------------------------------------ HTTP
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.png': 'image/png',
  '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.jpg': 'image/jpeg', '.webp': 'image/webp'
};
const ROUTES = { '/': 'index.html', '/operador': 'operador.html', '/tablero': 'tablero.html', '/posesion': 'posesion.html' };

function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
}

// IPs de este equipo en la red local.
// En Android (Termux) Node no puede leer las interfaces de red, así que se prueba
// con "ifconfig"/"ip" y además se aprende la IP real cuando un dispositivo se conecta.
const seenIPs = new Set();
let ipCache = { at: 0, list: [] };
const usableIP = (ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip) && !/^(127\.|169\.254\.|0\.)/.test(ip);

function lanIPs() {
  if (now() - ipCache.at < 5000) return ipCache.list;
  const found = new Set();
  try {
    for (const list of Object.values(os.networkInterfaces())) {
      for (const i of list || []) if ((i.family === 'IPv4' || i.family === 4) && !i.internal) found.add(i.address);
    }
  } catch (e) { /* Android: sin permiso para leer interfaces */ }
  if (!found.size) {
    const { execSync } = require('child_process');
    for (const cmd of ['ifconfig 2>/dev/null', 'ip -4 -o addr show 2>/dev/null']) {
      try {
        const out = execSync(cmd, { encoding: 'utf8', timeout: 1500 });
        for (const m of out.matchAll(/inet (?:addr:)?(\d+\.\d+\.\d+\.\d+)/g)) found.add(m[1]);
      } catch (e) { /* comando no disponible */ }
      if (found.size) break;
    }
  }
  for (const ip of seenIPs) found.add(ip);
  ipCache = { at: now(), list: [...found].filter(usableIP) };
  return ipCache.list;
}

const server = http.createServer((req, res) => {
  const p = new URL(req.url, 'http://local').pathname;
  const local = String(req.socket.localAddress || '').replace(/^::ffff:/, '');
  if (usableIP(local) && !seenIPs.has(local)) { seenIPs.add(local); ipCache.at = 0; }

  if (p === '/events') {
    req.socket.setNoDelay(true);
    req.socket.setTimeout(0);
    res.writeHead(200, {
      'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache',
      'Connection': 'keep-alive', 'Access-Control-Allow-Origin': '*'
    });
    res.write('retry: 1000\n\n');
    res.write('data: ' + payload() + '\n\n');
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }

  if (p === '/api/action' && req.method === 'POST') {
    let body = '';
    req.on('data', c => { body += c; if (body.length > 1e6) req.destroy(); });
    req.on('end', () => {
      try { dispatch(JSON.parse(body || '{}')); json(res, 200, { ok: true }); }
      catch (e) { json(res, 400, { ok: false, error: e.message }); }
    });
    return;
  }

  if (p === '/api/info') return json(res, 200, { ips: lanIPs(), port: PORT, screens: clients.size });
  if (p === '/api/state') return json(res, 200, JSON.parse(payload()));

  const file = ROUTES[p] || decodeURIComponent(p).replace(/^\/+/, '');
  const full = path.normalize(path.join(PUBLIC_DIR, file));
  if (!full.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end(); }
  fs.readFile(full, (err, data) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('No encontrado'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(full).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') console.error(`\n  El puerto ${PORT} ya está en uso. ¿Hay otro servidor abierto? Cerralo o usá otro puerto.\n`);
  else console.error(e);
  process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = lanIPs();
  const line = '  ' + '─'.repeat(58);
  console.log('\n' + line);
  console.log('   CÚMULO HOOPS · servidor de tablero listo');
  console.log(line);
  console.log(`   En este mismo equipo:   http://localhost:${PORT}  (QR para conectar)`);
  console.log(`   Operador aquí:          http://localhost:${PORT}/operador`);
  if (!ips.length) {
    console.log('\n   ⚠ No se pudo detectar la IP de este equipo.');
    console.log('     Conectá una TV o PC al hotspot y abrí http://IP:' + PORT);
    console.log('     (la IP es la "puerta de enlace" que muestra ese dispositivo en su configuración de red).');
  }
  for (const ip of ips) {
    console.log(`\n   Red ${ip}`);
    console.log(`     Tablero TV:   http://${ip}:${PORT}/tablero`);
    console.log(`     Reloj 24 s:   http://${ip}:${PORT}/posesion`);
    console.log(`     Operador:     http://${ip}:${PORT}/operador`);
  }
  console.log(line + '\n   Dejá esta ventana abierta mientras dure el partido.\n');

  if (process.argv.includes('--abrir')) {
    const { exec } = require('child_process');
    if (process.platform === 'win32') exec(`start "" "http://localhost:${PORT}/operador"`);
    else if (process.platform === 'android') exec(`termux-open-url "http://localhost:${PORT}/"`, () => {});
  }
});
