(function () {
  'use strict';
  const H = window.Hoops;
  const $ = (id) => document.getElementById(id);
  let S = null;

  const img = (url, alt) => url ? `<img src="${H.esc(url)}" alt="${H.esc(alt || '')}" loading="lazy">` : '';
  const avatar = (p) => `<div class="avatar" data-photo="${p.id}" title="Foto">${p.photo ? img(p.photo, p.name) : H.esc(H.initials(p.name))}</div>`;
  const crest = (t) => t.logo ? img(t.logo, t.name) : H.esc(t.short || H.initials(t.name));
  const byNum = (a, b) => parseInt(a.num, 10) - parseInt(b.num, 10);
  const findP = (side, id) => S && S[side].players.find(p => p.id === id);

  // ---------- esqueleto de cada equipo
  const skeleton = (side) => `
    <div class="club">
      <div>
        <div class="crest big t-crest" data-crest title="Foto del escudo"></div>
        <div class="club-btns">
          <button type="button" class="primary" data-crest>📷 Escudo</button>
          <button type="button" class="danger" data-crest-rm>Quitar escudo</button>
        </div>
      </div>
      <form class="club-form" autocomplete="off">
        <label>${side === 'home' ? 'Local' : 'Visitante'} · nombre del club<input name="name" maxlength="60" required></label>
        <div class="row3">
          <label>Sigla (hasta 4)<input name="short" maxlength="4" required></label>
          <label>Color<input type="color" name="color"></label>
        </div>
        <button type="submit" class="primary">Guardar club</button>
        <div class="saved"></div>
      </form>
    </div>
    <div class="roster-head"><span>Plantel</span><span class="t-count"></span></div>
    <div class="roster"></div>
    <p class="legend">● = en cancha (titular). Tocá la foto para sacarla o elegirla; ✎ para editar número y nombre.</p>
    <button class="add-player" data-add>+ Agregar jugador</button>
    <details class="paste">
      <summary>Pegar el plantel completo</summary>
      <p class="muted">Un jugador por línea: número y nombre (ej. <code>7 Juan Pérez</code>). Los que tienen el mismo número conservan sus puntos y su foto; los que no estén en la lista se quitan.</p>
      <textarea rows="10"></textarea>
      <div class="row"><button type="button" class="primary" data-paste>Reemplazar plantel</button></div>
    </details>`;
  for (const side of ['home', 'away']) $(side).innerHTML = skeleton(side);

  // ---------- render
  const dirty = { home: false, away: false }; // no pisar lo que se está escribiendo
  function renderTeam(side, t) {
    const el = $(side);
    el.style.setProperty('--c', t.color);
    el.querySelector('.t-crest').innerHTML = crest(t);
    el.querySelector('[data-crest-rm]').hidden = !t.logo;
    const f = el.querySelector('.club-form').elements;
    if (!dirty[side]) { f.name.value = t.name; f.short.value = t.short; f.color.value = t.color; }
    const on = t.players.filter(p => p.onCourt).length;
    el.querySelector('.t-count').textContent = `${t.players.length} jugadores · ${on} en cancha`;
    el.querySelector('.roster').innerHTML = t.players.slice().sort(byNum).map(p => `
      <div class="nr${p.onCourt ? ' on' : ''}">
        ${avatar(p)}
        <span class="pnum">${H.esc(p.num)}</span>
        <span class="pname">${H.esc(p.name)}</span>
        <span class="st">${p.points} pts · ${p.fouls} F</span>
        <button class="court" data-court="${p.id}" title="${p.onCourt ? 'Sacar de la cancha' : 'Poner en cancha'}">${p.onCourt ? '●' : '○'}</button>
        <button class="edit" data-edit="${p.id}" title="Editar">✎</button>
      </div>`).join('') || '<p class="empty">Todavía no hay jugadores.</p>';
    el.querySelector('[data-add]').disabled = t.players.length >= 20;
    const ta = el.querySelector('.paste textarea');
    if (document.activeElement !== ta && !el.querySelector('.paste').open) {
      ta.value = t.players.slice().sort(byNum).map(p => `${p.num} ${p.name}`).join('\n');
    }
  }
  function render(s) {
    S = s;
    renderTeam('home', s.home);
    renderTeam('away', s.away);
    fillPlayer();
  }

  // ---------- acciones
  function toast(msg) {
    const t = $('toast');
    t.textContent = msg; t.style.display = 'block';
    clearTimeout(toast.t); toast.t = setTimeout(() => { t.style.display = 'none'; }, 3000);
  }
  function send(type, data) { return H.send(type, data).catch(e => { toast(e.message); throw e; }); }
  function photoFlow(opts) {
    return H.pickPhoto(opts).then(() => toast('✓ Foto guardada')).catch(e => { if (e.message !== 'cancelado') toast(e.message); });
  }

  for (const side of ['home', 'away']) {
    const el = $(side);
    const form = el.querySelector('.club-form');
    form.addEventListener('input', () => { dirty[side] = true; });
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const f = form.elements;
      send('team', { team: side, name: f.name.value, short: f.short.value, color: f.color.value }).then(() => {
        dirty[side] = false;
        el.querySelector('.saved').textContent = '✓ Guardado';
        setTimeout(() => { el.querySelector('.saved').textContent = ''; }, 2500);
        if (S) renderTeam(side, S[side]);
      }).catch(() => {});
    });
    el.addEventListener('click', (e) => {
      const t = e.target.closest('[data-crest], [data-crest-rm], [data-court], [data-edit], [data-photo], [data-add], [data-paste]');
      if (!t || !S) return;
      if (t.hasAttribute('data-crest')) photoFlow({ team: side, mode: 'crest', title: 'Escudo de ' + S[side].name });
      else if (t.hasAttribute('data-crest-rm')) { if (confirm('¿Quitar el escudo?')) send('removePhoto', { team: side }).catch(() => {}); }
      else if (t.hasAttribute('data-court')) send('sub', { team: side, playerId: Number(t.getAttribute('data-court')) }).catch(() => {});
      else if (t.hasAttribute('data-edit')) openPlayer(side, Number(t.getAttribute('data-edit')));
      else if (t.hasAttribute('data-photo')) {
        const p = findP(side, Number(t.getAttribute('data-photo')));
        if (p) photoFlow({ team: side, playerId: p.id, mode: 'player', title: `#${p.num} ${p.name}` });
      }
      else if (t.hasAttribute('data-add')) openPlayer(side, null);
      else if (t.hasAttribute('data-paste')) {
        const text = el.querySelector('.paste textarea').value;
        if (!confirm('¿Reemplazar el plantel por esta lista?')) return;
        const msg = {}; msg[side] = { roster: text };
        send('config', msg).then(() => { el.querySelector('.paste').open = false; toast('✓ Plantel actualizado'); }).catch(() => {});
      }
    });
  }

  // ---------- jugador
  let editing = null; // { team, id|null }
  const pf = $('player-form');
  function openPlayer(team, id) {
    editing = { team, id };
    const p = id != null ? findP(team, id) : null;
    const f = pf.elements;
    $('player-title').textContent = p ? `Jugador · ${S[team].short}` : `Nuevo jugador · ${S[team].short}`;
    f.num.value = p ? p.num : '';
    f.name.value = p ? p.name : '';
    f.onCourt.checked = p ? p.onCourt : false;
    $('player-remove').hidden = !p;
    $('dlg-player').style.setProperty('--c', S[team].color);
    fillPlayer();
    $('dlg-player').showModal();
    if (!p) setTimeout(() => f.num.focus(), 50);
  }
  function fillPlayer() {
    if (!editing || !S) return;
    const p = editing.id != null ? findP(editing.team, editing.id) : null;
    if (editing.id != null && !p) { $('dlg-player').close(); return; }
    const name = pf.elements.name.value || (p && p.name) || '';
    $('player-photo-view').innerHTML = p && p.photo ? img(p.photo, p.name) : H.esc(H.initials(name || '?'));
    $('player-photo').disabled = !p;
    $('player-photo-rm').hidden = !(p && p.photo);
    $('player-photo-hint').textContent = p ? 'Sacale una foto o elegila de la galería.' : 'Guardá el jugador primero para agregarle la foto.';
    $('player-stats').innerHTML = p ? [
      ['PTS', p.points], ['TL', p.ft || 0], ['2P', p.fg2 || 0], ['3P', p.fg3 || 0], ['F', p.fouls]
    ].map(([k, v]) => `<div><b>${v}</b><small>${k}</small></div>`).join('') : '';
  }
  $('player-photo').addEventListener('click', () => {
    const p = findP(editing.team, editing.id);
    if (p) photoFlow({ team: editing.team, playerId: p.id, mode: 'player', title: `#${p.num} ${p.name}` });
  });
  $('player-photo-rm').addEventListener('click', () => {
    if (confirm('¿Quitar la foto?')) send('removePhoto', { team: editing.team, playerId: editing.id }).catch(() => {});
  });
  $('player-remove').addEventListener('click', () => {
    const p = findP(editing.team, editing.id);
    if (p && confirm(`¿Quitar a #${p.num} ${p.name} del plantel? Se pierden sus estadísticas de este partido.`)) {
      send('removePlayer', { team: editing.team, playerId: p.id }).then(() => $('dlg-player').close()).catch(() => {});
    }
  });
  pf.addEventListener('submit', (e) => {
    e.preventDefault();
    const f = pf.elements;
    const msg = { team: editing.team, num: f.num.value.trim(), name: f.name.value, onCourt: f.onCourt.checked };
    if (editing.id != null) msg.playerId = editing.id;
    send('player', msg).then(() => $('dlg-player').close()).catch(() => {});
  });
  $('dlg-player').addEventListener('close', () => { editing = null; });
  document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => b.closest('dialog').close()));

  H.connect(render, (ok) => {
    $('conn').classList.toggle('ok', ok);
    $('conn').querySelector('span').textContent = ok ? 'En vivo' : 'Sin conexión';
  }, 'operador');
})();
