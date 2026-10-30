(function () {
  'use strict';
  const H = window.Hoops;
  const $ = (id) => document.getElementById(id);
  let S = null;

  // Jugador seleccionado en cada equipo: lo que se anota (+1/+2/+3, falta) se le imputa a él.
  // Es propio de este dispositivo: cada operador tiene su selección.
  const selected = { home: null, away: null };
  try {
    const saved = JSON.parse(sessionStorage.getItem('hoops-sel') || '{}');
    if (saved.home) selected.home = saved.home;
    if (saved.away) selected.away = saved.away;
  } catch (e) { /* sin almacenamiento */ }
  function saveSel() { try { sessionStorage.setItem('hoops-sel', JSON.stringify(selected)); } catch (e) { /* nada */ } }

  // ---------- esqueleto de cada equipo
  const skeleton = `
    <div class="t-head">
      <div class="crest t-crest"></div>
      <div class="t-name"></div>
      <div class="t-score">0</div>
    </div>
    <div class="target"><span class="t-target"></span><button class="clear-sel" data-clear title="Anotar sin jugador">✕</button></div>
    <div class="row4">
      <button data-a="score" data-pts="1" data-sel>+1</button>
      <button data-a="score" data-pts="2" data-sel>+2</button>
      <button data-a="score" data-pts="3" data-sel class="three">+3</button>
      <button data-a="foul" data-delta="1" data-sel class="foul-btn">Falta</button>
    </div>
    <div class="row-sub">
      <button class="sub-btn" data-sub>⇄ Cambio</button>
      <button data-a="score" data-pts="-1" data-sel class="ghost" title="Corrige un punto del jugador seleccionado">−1</button>
    </div>
    <div class="counters">
      <div class="counter"><span>Faltas<i class="lg"> equipo</i></span><em class="t-bonus">BONUS</em>
        <div><button data-a="foul" data-delta="-1">−</button><b class="t-fouls">0</b><button data-a="foul" data-delta="1">+</button></div></div>
      <div class="counter"><span><i class="lg">Tiempos muertos</i><i class="sh">Tiempos</i></span>
        <div><button data-a="timeout" data-delta="-1">−</button><b class="t-to">0</b><button data-a="timeout" data-delta="1">+</button></div></div>
    </div>
    <div class="pl-head"><span>En cancha · tocá para seleccionar</span><span class="t-oncourt"></span></div>
    <div class="players"></div>`;
  for (const side of ['home', 'away']) $(side).innerHTML = skeleton;

  const img = (url, alt) => url ? `<img src="${H.esc(url)}" alt="${H.esc(alt || '')}" loading="lazy">` : '';
  const avatar = (p) => `<div class="avatar">${p.photo ? img(p.photo, p.name) : H.esc(H.initials(p.name))}</div>`;
  const crest = (t) => t.logo ? img(t.logo, t.name) : H.esc(t.short || H.initials(t.name));
  const byNum = (a, b) => parseInt(a.num, 10) - parseInt(b.num, 10);
  const findP = (side, id) => S && S[side].players.find(p => p.id === id);

  // ---------- render
  function renderTeam(side, t) {
    const el = $(side);
    el.style.setProperty('--c', t.color);
    el.querySelector('.t-name').textContent = t.name;
    el.querySelector('.t-crest').innerHTML = crest(t);
    el.querySelector('.t-score').textContent = t.score;
    el.querySelector('.t-fouls').textContent = t.fouls;
    el.querySelector('.t-to').textContent = t.timeouts;
    el.querySelector('.t-bonus').classList.toggle('show', t.fouls >= 4);

    const on = t.players.filter(p => p.onCourt).sort(byNum);
    // Si el seleccionado ya no está en cancha (salió, lo quitaron), se deselecciona
    if (selected[side] && !on.some(p => p.id === selected[side])) { selected[side] = null; saveSel(); }
    const sel = findP(side, selected[side]);

    const oc = el.querySelector('.t-oncourt');
    oc.textContent = `${on.length} de 5`;
    oc.classList.toggle('bad', on.length !== 5);
    el.querySelector('.t-target').innerHTML = sel
      ? `Anota <b>#${H.esc(sel.num)} ${H.esc(sel.name)}</b>`
      : 'Anota <b>el equipo</b> · elegí un jugador abajo';
    el.querySelector('.target').classList.toggle('has', !!sel);
    el.querySelector('[data-sub]').disabled = !sel;

    let html = on.map(p => `
      <button class="oc${p.id === selected[side] ? ' sel' : ''}${p.fouls >= 5 ? ' out' : ''}" data-select="${p.id}">
        ${avatar(p)}
        <span class="pnum">${H.esc(p.num)}</span>
        <span class="pname">${H.esc(p.name)}</span>
        <span class="pts" title="Puntos">${p.points}<small>pts</small></span>
        <span class="pf f${Math.min(p.fouls, 5)}" title="Faltas">${p.fouls}<small>F</small></span>
      </button>`).join('');
    for (let i = on.length; i < 5; i++) html += `<button class="oc empty-slot" data-enter>+ Ingresar jugador</button>`;
    if (!t.players.length) html = `<p class="empty">No hay jugadores cargados. Cargalos en <a href="/nomina">📋 Nómina</a>.</p>`;
    el.querySelector('.players').innerHTML = html;
  }

  function render(s) {
    S = s;
    $('title').textContent = s.title;
    renderTeam('home', s.home);
    renderTeam('away', s.away);
    $('period-label').textContent = H.periodName(s);
    document.querySelectorAll('#periods [data-n]').forEach(b => {
      if (b.id !== 'btn-ot') b.classList.toggle('active', Number(b.dataset.n) === s.period);
    });
    const ot = $('btn-ot'), inOT = s.period > s.periods;
    ot.dataset.n = inOT ? s.period + 1 : s.periods + 1;
    ot.textContent = inOT ? '+OT' : 'OT';
    ot.classList.toggle('active', inOT);

    const bc = $('btn-clock');
    bc.textContent = s.clock.running ? '⏸ DETENER' : '▶ INICIAR';
    bc.classList.toggle('running', s.clock.running);
    $('btn-shot').textContent = s.shot.running ? '⏸' : '▶';
    $('poss-home').textContent = '◀ ' + s.home.short;
    $('poss-away').textContent = s.away.short + ' ▶';
    $('poss-home').classList.toggle('active', s.possession === 'home');
    $('poss-away').classList.toggle('active', s.possession === 'away');
    $('log').innerHTML = s.log.slice(0, 80).map(l => `<li><span>${l.p} ${l.c}</span>${H.esc(l.text)}</li>`).join('');
    if ($('dlg-sub').open) renderBench();
    tick();
  }

  function tick() {
    if (!S) return;
    const g = H.rem(S.clock), sh = H.rem(S.shot), vis = H.shotVisible(S);
    $('clock').textContent = H.fmtGame(g);
    $('shot').textContent = vis ? H.fmtShot(sh) : '––';
    $('shot').classList.toggle('low', vis && S.shot.running && sh < 5000);
  }
  setInterval(tick, 50);

  // ---------- envío de acciones
  function toast(msg) {
    const t = $('toast');
    t.textContent = msg; t.style.display = 'block';
    clearTimeout(toast.t); toast.t = setTimeout(() => { t.style.display = 'none'; }, 3000);
  }
  function send(type, data) { return H.send(type, data).catch(e => { toast(e.message); throw e; }); }
  const quiet = (p) => p.catch(() => {});

  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-a]');
    if (!b || b.closest('dialog')) return;
    const d = b.dataset, a = d.a, msg = {};
    const holder = b.closest('[data-team]');
    if (d.team) msg.team = d.team; else if (holder) msg.team = holder.dataset.team;
    for (const k of ['pts', 'delta', 'ms', 's', 'n']) if (d[k] !== undefined) msg[k] = Number(d[k]);
    // Botones de canasta y falta: al jugador seleccionado de ese equipo
    if (d.sel !== undefined && msg.team && selected[msg.team]) msg.playerId = selected[msg.team];

    if (a === 'period' && S) {
      const label = msg.n <= S.periods ? 'el cuarto ' + msg.n : 'tiempo suplementario';
      const q = msg.n === S.period ? '¿Reiniciar el reloj de este período?' : `¿Pasar a ${label}? El reloj vuelve a cero y se reinicia la posesión.`;
      if (H.rem(S.clock) > 0 && !confirm(q)) return;
    }
    quiet(send(a, msg));
    b.blur();
    if (navigator.vibrate) navigator.vibrate(12);
  });

  // ---------- selección de jugador
  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-select], [data-clear], [data-sub], [data-enter]');
    if (!t || t.closest('dialog') || !S) return;
    const side = t.closest('[data-team]').dataset.team;
    if (t.hasAttribute('data-select')) {
      const id = Number(t.getAttribute('data-select'));
      selected[side] = selected[side] === id ? null : id; // tocar de nuevo deselecciona
      saveSel();
      renderTeam(side, S[side]);
      if (navigator.vibrate) navigator.vibrate(8);
    } else if (t.hasAttribute('data-clear')) {
      selected[side] = null; saveSel(); renderTeam(side, S[side]);
    } else if (t.hasAttribute('data-sub')) {
      if (selected[side]) openSub(side, selected[side]);
    } else if (t.hasAttribute('data-enter')) {
      openSub(side, null);
    }
  });

  // ---------- cambio: sale el seleccionado, se elige quién entra
  let subCtx = null; // { side, outId|null }
  function openSub(side, outId) {
    subCtx = { side, outId };
    $('dlg-sub').style.setProperty('--c', S[side].color);
    renderBench();
    $('dlg-sub').showModal();
  }
  function renderBench() {
    if (!subCtx || !S) return;
    const t = S[subCtx.side];
    const out = subCtx.outId != null ? findP(subCtx.side, subCtx.outId) : null;
    if (subCtx.outId != null && (!out || !out.onCourt)) { $('dlg-sub').close(); return; }
    $('sub-title').textContent = (out ? 'Cambio · ' : 'Ingresar jugador · ') + t.short;
    $('sub-out').innerHTML = out
      ? `<span class="tag-out">SALE</span>${avatar(out)}<b>#${H.esc(out.num)}</b> ${H.esc(out.name)}<span class="muted">${out.points} pts · ${out.fouls} F</span>`
      : '';
    $('sub-out').hidden = !out;
    $('sub-only-out').hidden = !out;
    $('sub-label').textContent = out ? 'Elegí el reemplazante (ENTRA)' : 'Elegí quién entra a la cancha';
    const bench = t.players.filter(p => !p.onCourt).sort(byNum);
    $('bench').innerHTML = bench.map(p => `
      <button class="bench-p${p.fouls >= 5 ? ' out' : ''}" data-in="${p.id}">
        ${avatar(p)}<span class="pnum">${H.esc(p.num)}</span><span class="pname">${H.esc(p.name)}</span>
        <span class="muted">${p.points} pts · ${p.fouls} F${p.fouls >= 5 ? ' · 5 faltas' : ''}</span>
      </button>`).join('') || `<p class="empty">No hay suplentes. Agregalos en <a href="/nomina">📋 Nómina</a>.</p>`;
  }
  $('bench').addEventListener('click', (e) => {
    const b = e.target.closest('[data-in]');
    if (!b || !subCtx) return;
    const inId = Number(b.getAttribute('data-in'));
    const p = findP(subCtx.side, inId);
    if (p && p.fouls >= 5 && !confirm(`#${p.num} ${p.name} tiene 5 faltas. ¿Ingresarlo igual?`)) return;
    const { side, outId } = subCtx;
    const req = outId != null
      ? send('swap', { team: side, outId, inId })
      : send('sub', { team: side, playerId: inId });
    req.then(() => {
      if (outId != null) { selected[side] = inId; saveSel(); } // el que entra queda seleccionado
      $('dlg-sub').close();
    }).catch(() => {});
  });
  $('sub-only-out').addEventListener('click', () => {
    if (!subCtx || subCtx.outId == null) return;
    send('sub', { team: subCtx.side, playerId: subCtx.outId }).then(() => $('dlg-sub').close()).catch(() => {});
  });
  $('dlg-sub').addEventListener('close', () => { subCtx = null; });
  document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => b.closest('dialog').close()));

  // ---------- fijar el reloj a mano
  $('edit-clock').addEventListener('click', () => {
    const v = prompt('Tiempo de juego (m:ss o segundos, ej. 1:24 o 9.5)', S ? H.fmtGame(H.rem(S.clock)) : '');
    if (v == null) return;
    let ms = null, m;
    if ((m = v.trim().match(/^(\d{1,2}):(\d{1,2})(?:\.(\d))?$/))) ms = (+m[1] * 60 + +m[2]) * 1000 + (m[3] ? +m[3] * 100 : 0);
    else if ((m = v.trim().match(/^(\d{1,3})(?:[.,](\d))?$/))) ms = +m[1] * 1000 + (m[2] ? +m[2] * 100 : 0);
    if (ms == null) return toast('Formato no válido');
    quiet(send('clockSet', { ms }));
  });

  // Tocar el reloj también permite fijarlo (en el celular no hay lugar para el botón ✎)
  $('clock-box').addEventListener('click', () => $('edit-clock').click());

  // ---------- menú (celular)
  $('open-menu').addEventListener('click', () => $('dlg-menu').showModal());
  $('menu-config').addEventListener('click', () => { $('dlg-menu').close(); $('open-config').click(); });

  // ---------- atajos de teclado
  document.addEventListener('keydown', (e) => {
    if (document.querySelector('dialog[open]') || e.target.matches('input, textarea, select')) return;
    const k = e.key.toLowerCase();
    if (e.code === 'Space') { e.preventDefault(); quiet(send('clockToggle')); }
    else if ((e.ctrlKey || e.metaKey) && k === 'z') { e.preventDefault(); quiet(send('undo')); }
    else if (e.ctrlKey || e.metaKey || e.altKey) return;
    else if (k === 'r') quiet(send('shotReset', { s: 24 }));
    else if (k === 't') quiet(send('shotReset', { s: 14 }));
    else if (k === 'p') quiet(send('swapPossession'));
    else if (k === 'b') quiet(send('buzzer'));
  });

  // ---------- datos del partido
  const dlg = $('config'), form = $('config-form');
  $('open-config').addEventListener('click', () => {
    if (!S) return;
    const f = form.elements;
    f.title.value = S.title;
    f.periodMinutes.value = S.periodLength / 60000;
    f.otMinutes.value = S.otLength / 60000;
    dlg.showModal();
  });
  $('cancel-config').addEventListener('click', () => dlg.close());
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = form.elements;
    send('config', { title: f.title.value, periodMinutes: Number(f.periodMinutes.value), otMinutes: Number(f.otMinutes.value) })
      .then(() => dlg.close()).catch(() => {});
  });
  $('new-game').addEventListener('click', () => {
    if (confirm('¿Empezar un partido nuevo? Se borran puntos, faltas, tiempos y registro (se mantienen equipos, planteles y fotos).')) {
      send('newGame').then(() => dlg.close()).catch(() => {});
    }
  });
  $('logout').addEventListener('click', () => {
    H.post('/api/logout').finally(() => H.goLogin('operador'));
  });

  // ---------- direcciones para las TVs y sesión
  fetch('/api/info').then(r => r.json()).then(info => {
    $('urls').innerHTML = info.ips.length
      ? info.ips.slice(0, 1).map(ip => `TV tablero <b>http://${ip}:${info.port}/tablero</b> · Reloj 24 <b>http://${ip}:${info.port}/posesion</b>`).join('<br>')
      : 'Sin red detectada: conectá el servidor a un WiFi o activá el hotspot';
    $('logout').hidden = info.local || !(info.protected || {}).operador;
  }).catch(() => {});

  H.connect(render, (ok) => {
    $('conn').classList.toggle('ok', ok);
    $('conn2').classList.toggle('ok', ok);
    $('conn').querySelector('span').textContent = ok ? 'En vivo' : 'Sin conexión';
  }, 'operador');
})();
