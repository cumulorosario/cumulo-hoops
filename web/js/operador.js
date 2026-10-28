(function () {
  'use strict';
  const H = window.Hoops;
  const $ = (id) => document.getElementById(id);
  let S = null;

  // ---------- esqueleto de cada equipo
  const skeleton = `
    <div class="t-head"><div class="t-name"></div><div class="t-score">0</div></div>
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
    <div class="pl-head"><span>Jugadores · tocá ● para entrar/salir</span><span class="t-oncourt"></span></div>
    <div class="players"></div>`;
  for (const side of ['home', 'away']) $(side).innerHTML = skeleton;

  // ---------- render
  function renderTeam(side, t) {
    const el = $(side);
    el.style.setProperty('--c', t.color);
    el.querySelector('.t-name').textContent = t.name;
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
        <span class="pnum">${H.esc(p.num)}</span>
        <span class="pname">${H.esc(p.name)}</span>
        <span class="pts" title="Puntos">${p.points}</span>
        <span class="pf f${Math.min(p.fouls, 5)}" title="Faltas">${p.fouls}F</span>
        <button data-a="score" data-pid="${p.id}" data-pts="1">+1</button>
        <button data-a="score" data-pid="${p.id}" data-pts="2">+2</button>
        <button data-a="score" data-pid="${p.id}" data-pts="3">+3</button>
        <button class="fb" data-a="foul" data-pid="${p.id}" data-delta="1">F</button>
      </div>`).join('') || '<p class="empty">Cargá los jugadores en ⚙ Configurar</p>';
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
    clearTimeout(toast.t); toast.t = setTimeout(() => { t.style.display = 'none'; }, 2500);
  }
  function send(type, data) { return H.send(type, data).catch(e => toast('No se pudo enviar: ' + e.message)); }

  document.addEventListener('click', (e) => {
    const b = e.target.closest('[data-a]');
    if (!b) return;
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
    send(a, msg);
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
    send('clockSet', { ms });
  });

  // ---------- atajos de teclado
  document.addEventListener('keydown', (e) => {
    if ($('config').open || e.target.matches('input, textarea')) return;
    const k = e.key.toLowerCase();
    if (e.code === 'Space') { e.preventDefault(); send('clockToggle'); }
    else if ((e.ctrlKey || e.metaKey) && k === 'z') { e.preventDefault(); send('undo'); }
    else if (e.ctrlKey || e.metaKey || e.altKey) return;
    else if (k === 'r') send('shotReset', { s: 24 });
    else if (k === 't') send('shotReset', { s: 14 });
    else if (k === 'p') send('swapPossession');
    else if (k === 'b') send('buzzer');
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
      const t = S[side];
      f[side + '_name'].value = t.name;
      f[side + '_short'].value = t.short;
      f[side + '_color'].value = t.color;
      f[side + '_roster'].value = t.players.map(p => `${p.num} ${p.name}`).join('\n');
    }
    dlg.showModal();
  });
  $('cancel-config').addEventListener('click', () => dlg.close());
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = form.elements, team = (side) => ({
      name: f[side + '_name'].value, short: f[side + '_short'].value,
      color: f[side + '_color'].value, roster: f[side + '_roster'].value
    });
    send('config', {
      title: f.title.value, periodMinutes: Number(f.periodMinutes.value), otMinutes: Number(f.otMinutes.value),
      home: team('home'), away: team('away')
    }).then(() => dlg.close());
  });
  $('new-game').addEventListener('click', () => {
    if (confirm('¿Empezar un partido nuevo? Se borran puntos, faltas, tiempos y registro (se mantienen equipos y planteles).')) {
      send('newGame').then(() => dlg.close());
    }
  });

  // ---------- direcciones para las TVs
  fetch('/api/info').then(r => r.json()).then(info => {
    $('urls').innerHTML = info.ips.length
      ? info.ips.map(ip => `TV tablero <b>http://${ip}:${info.port}/tablero</b> · Reloj 24 <b>http://${ip}:${info.port}/posesion</b>`).join('<br>')
      : 'Sin red detectada: conectá la compu a un WiFi o activá el hotspot';
  }).catch(() => {});

  H.connect(render, (ok) => {
    $('conn').classList.toggle('ok', ok);
    $('conn').querySelector('span').textContent = ok ? 'En vivo' : 'Sin conexión';
  });
})();
