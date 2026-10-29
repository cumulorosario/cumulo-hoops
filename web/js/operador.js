(function () {
  'use strict';
  const H = window.Hoops;
  const $ = (id) => document.getElementById(id);
  let S = null;

  // ---------- esqueleto de cada equipo
  const skeleton = `
    <div class="t-head">
      <button class="crest-btn" data-edit-team title="Escudo y datos del equipo"><div class="crest t-crest"></div></button>
      <button class="t-title" data-edit-team title="Editar equipo"><span class="t-name"></span><span class="pencil">✎</span></button>
      <div class="t-score">0</div>
    </div>
    <div class="row4">
      <button data-a="score" data-pts="1">+1</button>
      <button data-a="score" data-pts="2">+2</button>
      <button data-a="score" data-pts="3" class="three">+3</button>
      <button data-a="score" data-pts="-1" class="ghost">−1</button>
    </div>
    <div class="counters">
      <div class="counter"><span>Faltas equipo</span><em class="t-bonus">BONUS</em>
        <div><button data-a="foul" data-delta="-1">−</button><b class="t-fouls">0</b><button data-a="foul" data-delta="1">+</button></div></div>
      <div class="counter"><span>Tiempos muertos</span>
        <div><button data-a="timeout" data-delta="-1">−</button><b class="t-to">0</b><button data-a="timeout" data-delta="1">+</button></div></div>
    </div>
    <div class="pl-head"><span>● entra/sale · tocá el nombre para editar</span><span class="t-oncourt"></span></div>
    <div class="players"></div>
    <button class="add-player" data-add-player>+ Agregar jugador</button>`;
  for (const side of ['home', 'away']) $(side).innerHTML = skeleton;

  const img = (url, alt) => url ? `<img src="${H.esc(url)}" alt="${H.esc(alt || '')}" loading="lazy">` : '';
  const avatar = (p) => `<div class="avatar">${p.photo ? img(p.photo, p.name) : H.esc(H.initials(p.name))}</div>`;
  const crest = (t) => t.logo ? img(t.logo, t.name) : H.esc(t.short || H.initials(t.name));

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
    const on = t.players.filter(p => p.onCourt).length;
    const oc = el.querySelector('.t-oncourt');
    oc.textContent = `En cancha: ${on}`;
    oc.classList.toggle('bad', on !== 5);
    const list = t.players.slice().sort((a, b) => (b.onCourt - a.onCourt) || (parseInt(a.num, 10) - parseInt(b.num, 10)));
    el.querySelector('.players').innerHTML = list.map(p => `
      <div class="pl${p.onCourt ? ' on' : ''}${p.fouls >= 5 ? ' out' : ''}">
        <button class="pin" data-a="sub" data-pid="${p.id}" title="${p.onCourt ? 'Sacar' : 'Ingresar'}">${p.onCourt ? '●' : '○'}</button>
        <button class="photo-btn" data-edit-player="${p.id}" title="Foto y datos">${avatar(p)}</button>
        <button class="pedit" data-edit-player="${p.id}" title="Editar"><span class="pnum">${H.esc(p.num)}</span><span class="pname">${H.esc(p.name)}</span></button>
        <span class="pts" title="Puntos">${p.points}</span>
        <span class="pf f${Math.min(p.fouls, 5)}" title="Faltas">${p.fouls}F</span>
        <button data-a="score" data-pid="${p.id}" data-pts="1">+1</button>
        <button data-a="score" data-pid="${p.id}" data-pts="2">+2</button>
        <button data-a="score" data-pid="${p.id}" data-pts="3">+3</button>
        <button class="fb" data-a="foul" data-pid="${p.id}" data-delta="1">F</button>
      </div>`).join('') || '<p class="empty">Todavía no hay jugadores. Agregalos con el botón de abajo.</p>';
    el.querySelector('[data-add-player]').disabled = t.players.length >= 20;
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
    refreshOpenDialogs();
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
    if (d.pid) msg.playerId = Number(d.pid);
    for (const k of ['pts', 'delta', 'ms', 's', 'n']) if (d[k] !== undefined) msg[k] = Number(d[k]);

    if (a === 'period' && S) {
      const label = msg.n <= S.periods ? 'el cuarto ' + msg.n : 'tiempo suplementario';
      const q = msg.n === S.period ? '¿Reiniciar el reloj de este período?' : `¿Pasar a ${label}? El reloj vuelve a cero y se reinicia la posesión.`;
      if (H.rem(S.clock) > 0 && !confirm(q)) return;
    }
    quiet(send(a, msg));
    b.blur();
    if (navigator.vibrate) navigator.vibrate(12);
  });

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

  // ---------- diálogos: cerrar
  document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => b.closest('dialog').close()));

  function photoFlow(opts) {
    return H.pickPhoto(opts).then(() => { toast('✓ Foto guardada'); }).catch(e => {
      if (e.message !== 'cancelado') toast(e.message);
    });
  }

  // ---------- equipo
  let editingTeam = null;
  const teamForm = $('team-form');
  function openTeam(side) {
    editingTeam = side;
    const t = S[side];
    teamForm.elements.name.value = t.name;
    teamForm.elements.short.value = t.short;
    teamForm.elements.color.value = t.color;
    fillTeamPhoto();
    $('dlg-team').showModal();
  }
  function fillTeamPhoto() {
    if (!editingTeam || !S) return;
    const t = S[editingTeam];
    $('team-crest').innerHTML = crest(t);
    $('team-photo-rm').hidden = !t.logo;
  }
  $('team-photo').addEventListener('click', () => photoFlow({ team: editingTeam, mode: 'crest', title: 'Escudo de ' + S[editingTeam].name }));
  $('team-photo-rm').addEventListener('click', () => {
    if (confirm('¿Quitar el escudo?')) quiet(send('removePhoto', { team: editingTeam }));
  });
  teamForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = teamForm.elements;
    send('team', { team: editingTeam, name: f.name.value, short: f.short.value, color: f.color.value })
      .then(() => $('dlg-team').close()).catch(() => {});
  });

  // ---------- jugador
  let editingPlayer = null; // { team, id|null }
  const playerForm = $('player-form');
  function findPlayer(team, id) { return S && S[team].players.find(p => p.id === id); }
  function openPlayer(team, id) {
    editingPlayer = { team, id };
    const p = id != null ? findPlayer(team, id) : null;
    const f = playerForm.elements;
    $('player-title').textContent = p ? `Jugador · ${S[team].short}` : `Nuevo jugador · ${S[team].short}`;
    f.num.value = p ? p.num : '';
    f.name.value = p ? p.name : '';
    f.onCourt.checked = p ? p.onCourt : false;
    $('player-remove').hidden = !p;
    $('dlg-player').style.setProperty('--c', S[team].color);
    fillPlayerPhoto();
    $('dlg-player').showModal();
    if (!p) setTimeout(() => f.num.focus(), 50);
  }
  function fillPlayerPhoto() {
    if (!editingPlayer || !S) return;
    const p = editingPlayer.id != null ? findPlayer(editingPlayer.team, editingPlayer.id) : null;
    if (editingPlayer.id != null && !p) { $('dlg-player').close(); return; }
    const name = playerForm.elements.name.value || (p && p.name) || '';
    $('player-photo-view').innerHTML = p && p.photo ? img(p.photo, p.name) : H.esc(H.initials(name || '?'));
    $('player-photo').disabled = !p;
    $('player-photo-rm').hidden = !(p && p.photo);
    $('player-photo-hint').textContent = p ? 'Sacale una foto o elegila de la galería.' : 'Guardá el jugador primero para agregarle la foto.';
    $('player-stats').innerHTML = p ? [
      ['PTS', p.points], ['TL', p.ft || 0], ['2P', p.fg2 || 0], ['3P', p.fg3 || 0], ['F', p.fouls]
    ].map(([k, v]) => `<div><b>${v}</b><small>${k}</small></div>`).join('') : '';
  }
  $('player-photo').addEventListener('click', () => {
    const p = findPlayer(editingPlayer.team, editingPlayer.id);
    if (p) photoFlow({ team: editingPlayer.team, playerId: p.id, mode: 'player', title: `#${p.num} ${p.name}` });
  });
  $('player-photo-rm').addEventListener('click', () => {
    if (confirm('¿Quitar la foto?')) quiet(send('removePhoto', { team: editingPlayer.team, playerId: editingPlayer.id }));
  });
  $('player-remove').addEventListener('click', () => {
    const p = findPlayer(editingPlayer.team, editingPlayer.id);
    if (p && confirm(`¿Quitar a #${p.num} ${p.name} del plantel? Se pierden sus estadísticas de este partido.`)) {
      send('removePlayer', { team: editingPlayer.team, playerId: p.id }).then(() => $('dlg-player').close()).catch(() => {});
    }
  });
  playerForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = playerForm.elements;
    const msg = { team: editingPlayer.team, num: f.num.value.trim(), name: f.name.value, onCourt: f.onCourt.checked };
    if (editingPlayer.id != null) msg.playerId = editingPlayer.id;
    send('player', msg).then(() => $('dlg-player').close()).catch(() => {});
  });
  ['dlg-team', 'dlg-player'].forEach(id => $(id).addEventListener('close', () => {
    if (id === 'dlg-team') editingTeam = null; else editingPlayer = null;
  }));
  function refreshOpenDialogs() { fillTeamPhoto(); fillPlayerPhoto(); }

  // Abrir edición desde la lista
  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-edit-team], [data-edit-player], [data-add-player]');
    if (!t || !S) return;
    const side = t.closest('[data-team]').dataset.team;
    if (t.hasAttribute('data-edit-team')) openTeam(side);
    else if (t.hasAttribute('data-add-player')) openPlayer(side, null);
    else openPlayer(side, Number(t.getAttribute('data-edit-player')));
  });

  // ---------- configuración
  const dlg = $('config'), form = $('config-form');
  $('open-config').addEventListener('click', () => {
    if (!S) return;
    const f = form.elements;
    f.title.value = S.title;
    f.periodMinutes.value = S.periodLength / 60000;
    f.otMinutes.value = S.otLength / 60000;
    for (const side of ['home', 'away']) {
      f[side + '_roster'].value = S[side].players.map(p => `${p.num} ${p.name}`).join('\n');
      f[side + '_roster'].dataset.orig = f[side + '_roster'].value;
    }
    dlg.showModal();
  });
  $('cancel-config').addEventListener('click', () => dlg.close());
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = form.elements, msg = {
      title: f.title.value, periodMinutes: Number(f.periodMinutes.value), otMinutes: Number(f.otMinutes.value)
    };
    // Los planteles solo se mandan si se tocaron
    for (const side of ['home', 'away']) {
      const ta = f[side + '_roster'];
      if (ta.value !== ta.dataset.orig) msg[side] = { roster: ta.value };
    }
    send('config', msg).then(() => dlg.close()).catch(() => {});
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
    $('conn').querySelector('span').textContent = ok ? 'En vivo' : 'Sin conexión';
  }, 'operador');
})();
