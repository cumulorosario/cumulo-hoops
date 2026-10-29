(function () {
  'use strict';
  const H = window.Hoops;
  const $ = (id) => document.getElementById(id);
  const esc = H.esc;
  let S = null;

  // ---------- cálculos
  function periodCount(s) {
    return Math.max(s.periods, s.period, s.home.byPeriod.length, s.away.byPeriod.length);
  }
  function periodName(s, i) { return i < s.periods ? 'Q' + (i + 1) : 'OT' + (i + 1 - s.periods); }
  function totals(t) {
    const sum = (k) => t.players.reduce((a, p) => a + (p[k] || 0), 0);
    return { pts: sum('points'), ft: sum('ft'), fg2: sum('fg2'), fg3: sum('fg3'), fouls: sum('fouls') };
  }
  function byNumber(a, b) { return parseInt(a.num, 10) - parseInt(b.num, 10); }
  function leader(t) {
    const p = t.players.slice().sort((a, b) => b.points - a.points)[0];
    return p && p.points > 0 ? p : null;
  }
  function isFinal(s) {
    return s.period >= s.periods && !s.clock.running && H.rem(s.clock) <= 0 && s.home.score !== s.away.score;
  }
  function stateText(s) {
    if (isFinal(s)) return 'Final';
    const left = H.rem(s.clock);
    if (s.period === 1 && left === s.periodLength && s.home.score + s.away.score === 0) return 'Por comenzar';
    return H.periodLabel(s) + ' · ' + H.fmtGame(left);
  }
  function dateText(s) {
    const d = new Date(s.gameDate || Date.now());
    const p = (n) => (n < 10 ? '0' : '') + n;
    return p(d.getDate()) + '/' + p(d.getMonth() + 1) + '/' + d.getFullYear();
  }

  // ---------- pantalla
  function crestHtml(t) { return t.logo ? `<img src="${esc(t.logo)}" alt="">` : esc(t.short); }

  function render(s) {
    S = s;
    document.documentElement.style.setProperty('--hc', s.home.color);
    document.documentElement.style.setProperty('--ac', s.away.color);
    $('match').innerHTML = `
      <div class="meta"><b>${esc(s.title)}</b> · ${dateText(s)}</div>
      <div class="score">
        <div class="tm"><div class="crest">${crestHtml(s.home)}</div><b>${esc(s.home.name)}</b></div>
        <div class="big"><span class="h">${s.home.score}</span><i>–</i><span class="a">${s.away.score}</span></div>
        <div class="tm"><div class="crest">${crestHtml(s.away)}</div><b>${esc(s.away.name)}</b></div>
      </div>
      <span class="state${isFinal(s) ? ' final' : ''}">${esc(stateText(s))}</span>`;

    const n = periodCount(s);
    let head = '<th class="l">Equipo</th>', rows = '';
    for (let i = 0; i < n; i++) head += `<th>${periodName(s, i)}</th>`;
    for (const side of ['home', 'away']) {
      const t = s[side];
      let r = `<td class="l"><b style="color:${t.color}">${esc(t.short)}</b></td>`;
      for (let i = 0; i < n; i++) r += `<td>${i < s.period || t.byPeriod[i] ? (t.byPeriod[i] || 0) : '–'}</td>`;
      rows += `<tr>${r}<td class="pts">${t.score}</td><td>${t.timeouts}</td></tr>`;
    }
    $('periods').innerHTML = `<h2>Parciales</h2><table><tr>${head}<th>Total</th><th>T. muertos</th></tr>${rows}</table>`;

    for (const side of ['home', 'away']) {
      const t = s[side], tot = totals(t), top = leader(t);
      const body = t.players.slice().sort(byNumber).map(p => `
        <tr class="${top && p.id === top.id ? 'top' : ''}">
          <td class="num">${esc(p.num)}</td>
          <td class="l">${p.photo ? `<img class="ph" src="${esc(p.photo)}" alt="">` : `<span class="ini">${esc(H.initials(p.name))}</span>`}${esc(p.name)}</td>
          <td class="pts">${p.points}</td><td>${p.ft || 0}</td><td>${p.fg2 || 0}</td><td>${p.fg3 || 0}</td>
          <td class="${p.fouls >= 5 ? 'f5' : ''}">${p.fouls}</td>
        </tr>`).join('');
      const unassigned = t.score - tot.pts;
      $('box-' + side).style.setProperty('--c', t.color);
      $('box-' + side).innerHTML = `
        <h2><i></i>${esc(t.name)}</h2>
        <table>
          <tr><th>#</th><th class="l">Jugador</th><th>PTS</th><th>TL</th><th>2P</th><th>3P</th><th>F</th></tr>
          ${body}
          <tr class="tot"><td></td><td class="l">Total${unassigned ? ` <span class="muted">(${unassigned > 0 ? '+' : ''}${unassigned} sin jugador)</span>` : ''}</td>
            <td class="pts">${t.score}</td><td>${tot.ft}</td><td>${tot.fg2}</td><td>${tot.fg3}</td><td>${tot.fouls}</td></tr>
        </table>`;
    }
  }

  // ---------- texto para WhatsApp
  function shareText(s) {
    const L = [];
    L.push(`🏀 *${s.title}* — ${dateText(s)}`);
    L.push(`*${s.home.name} ${s.home.score} – ${s.away.score} ${s.away.name}*`);
    L.push(stateText(s));
    const n = periodCount(s), parts = [];
    for (let i = 0; i < Math.min(n, s.period); i++) parts.push(`${periodName(s, i)} ${s.home.byPeriod[i] || 0}-${s.away.byPeriod[i] || 0}`);
    if (parts.length) L.push('Parciales: ' + parts.join(' · '));
    for (const side of ['home', 'away']) {
      const t = s[side];
      const list = t.players.slice().sort((a, b) => b.points - a.points || byNumber(a, b)).filter(p => p.points > 0 || p.fouls > 0);
      L.push('');
      L.push(`📊 *${t.name}*`);
      if (!list.length) L.push('Sin estadísticas por jugador');
      for (const p of list) {
        const det = [p.ft ? `TL ${p.ft}` : '', p.fg2 ? `2P ${p.fg2}` : '', p.fg3 ? `3P ${p.fg3}` : ''].filter(Boolean).join(' · ');
        L.push(`#${p.num} ${p.name}: ${p.points} pts${det ? ` (${det})` : ''}${p.fouls ? ` · ${p.fouls}F` : ''}`);
      }
    }
    L.push('');
    L.push('Cúmulo Hoops · Desarrollado por Cúmulo Ingeniería');
    return L.join('\n');
  }

  // ---------- planilla CSV (se abre en Excel)
  function csv(s) {
    const q = (v) => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
    const rows = [['Partido', s.title], ['Fecha', dateText(s)],
      [s.home.name, s.home.score], [s.away.name, s.away.score], [],
      ['Equipo', 'Número', 'Jugador', 'Puntos', 'Libres', 'Dobles', 'Triples', 'Faltas']];
    for (const side of ['home', 'away']) {
      for (const p of s[side].players.slice().sort(byNumber)) {
        rows.push([s[side].name, p.num, p.name, p.points, p.ft || 0, p.fg2 || 0, p.fg3 || 0, p.fouls]);
      }
    }
    return '﻿' + rows.map(r => r.map(q).join(';')).join('\r\n');
  }

  // ---------- imagen para compartir
  function loadImg(src) {
    return new Promise(res => {
      if (!src) return res(null);
      const i = new Image();
      i.onload = () => res(i);
      i.onerror = () => res(null);
      i.src = src;
    });
  }
  function fitText(c, text, max) {
    if (c.measureText(text).width <= max) return text;
    while (text.length > 1 && c.measureText(text + '…').width > max) text = text.slice(0, -1);
    return text + '…';
  }
  async function renderImage(s) {
    const W = 1080, PAD = 48;
    const rowsH = (t) => 56 + 44 + t.players.length * 42 + 52;
    const H0 = 520, PER = 170;
    const Ht = H0 + PER + rowsH(s.home) + rowsH(s.away) + 60 + 130;
    const cv = document.createElement('canvas');
    cv.width = W; cv.height = Ht;
    const c = cv.getContext('2d');
    const F = (w, px) => `${w} ${px}px system-ui, "Segoe UI", Roboto, Arial, sans-serif`;
    const [hl, al, logo] = await Promise.all([loadImg(s.home.logo), loadImg(s.away.logo), loadImg('/img/cumulo-logo.png')]);

    c.fillStyle = '#0b1020'; c.fillRect(0, 0, W, Ht);
    c.fillStyle = '#fbbf24'; c.font = F(800, 30); c.textAlign = 'center';
    c.fillText(s.title.toUpperCase(), W / 2, 80);
    c.fillStyle = '#8391a8'; c.font = F(600, 24);
    c.fillText(dateText(s) + '  ·  ' + stateText(s), W / 2, 120);

    // Equipos y marcador
    const drawTeam = (t, img, x) => {
      const size = 150, y = 160;
      c.fillStyle = 'rgba(255,255,255,.05)'; c.fillRect(x - size / 2, y, size, size);
      if (img) {
        const k = Math.min(size / img.width, size / img.height);
        c.drawImage(img, x - img.width * k / 2, y + (size - img.height * k) / 2, img.width * k, img.height * k);
      } else {
        c.fillStyle = t.color; c.font = F(900, 48); c.fillText(t.short, x, y + size / 2 + 16);
      }
      c.fillStyle = '#e6ebf4'; c.font = F(800, 28);
      c.fillText(fitText(c, t.name, 330), x, y + size + 48);
      c.fillStyle = t.color; c.font = F(900, 120);
      c.fillText(String(t.score), x, y + size + 180);
    };
    drawTeam(s.home, hl, W * 0.25);
    drawTeam(s.away, al, W * 0.75);
    c.fillStyle = '#334155'; c.font = F(900, 90); c.fillText('–', W / 2, 490);

    // Parciales
    let y = H0 + 20;
    const n = periodCount(s), colW = Math.min(110, (W - 2 * PAD - 200) / (n + 1));
    c.font = F(700, 20); c.fillStyle = '#8391a8'; c.textAlign = 'center';
    for (let i = 0; i < n; i++) c.fillText(periodName(s, i), PAD + 200 + colW * (i + .5), y);
    c.fillText('TOTAL', PAD + 200 + colW * (n + .5), y);
    for (const side of ['home', 'away']) {
      const t = s[side]; y += 50;
      c.textAlign = 'left'; c.fillStyle = t.color; c.font = F(900, 26); c.fillText(t.short, PAD, y);
      c.textAlign = 'center'; c.fillStyle = '#e6ebf4'; c.font = F(600, 26);
      for (let i = 0; i < n; i++) c.fillText(i < s.period || t.byPeriod[i] ? String(t.byPeriod[i] || 0) : '–', PAD + 200 + colW * (i + .5), y);
      c.font = F(900, 26); c.fillText(String(t.score), PAD + 200 + colW * (n + .5), y);
    }
    y = H0 + PER;

    // Planilla por equipo
    const cols = [[PAD + 10, 'l', '#'], [PAD + 80, 'l', 'JUGADOR'], [W - PAD - 360, 'c', 'PTS'], [W - PAD - 270, 'c', 'TL'],
      [W - PAD - 190, 'c', '2P'], [W - PAD - 110, 'c', '3P'], [W - PAD - 30, 'c', 'F']];
    for (const side of ['home', 'away']) {
      const t = s[side], tot = totals(t), top = leader(t);
      c.fillStyle = t.color; c.fillRect(PAD, y, 10, 34);
      c.textAlign = 'left'; c.fillStyle = '#e6ebf4'; c.font = F(800, 30); c.fillText(fitText(c, t.name, W - 2 * PAD - 30), PAD + 24, y + 29);
      y += 56;
      c.font = F(700, 18); c.fillStyle = '#8391a8';
      for (const [x, a, label] of cols) { c.textAlign = a === 'l' ? 'left' : 'center'; c.fillText(label, x, y + 22); }
      y += 44;
      for (const p of t.players.slice().sort(byNumber)) {
        c.fillStyle = 'rgba(255,255,255,.035)'; if ((y / 42 | 0) % 2) c.fillRect(PAD, y - 4, W - 2 * PAD, 42);
        const vals = [p.num, p.name, p.points, p.ft || 0, p.fg2 || 0, p.fg3 || 0, p.fouls];
        cols.forEach(([x, a], i) => {
          c.textAlign = a === 'l' ? 'left' : 'center';
          c.fillStyle = i === 0 ? t.color : (i === 2 && top && p.id === top.id) ? '#fbbf24' : (i === 6 && p.fouls >= 5) ? '#fca5a5' : '#e6ebf4';
          c.font = F(i === 0 || i === 2 ? 800 : 500, 24);
          c.fillText(i === 1 ? fitText(c, String(vals[i]), W - 2 * PAD - 520) : String(vals[i]), x, y + 26);
        });
        y += 42;
      }
      c.fillStyle = '#1f2a40'; c.fillRect(PAD, y + 2, W - 2 * PAD, 2);
      const tv = ['', 'TOTAL', t.score, tot.ft, tot.fg2, tot.fg3, tot.fouls];
      cols.forEach(([x, a], i) => { c.textAlign = a === 'l' ? 'left' : 'center'; c.fillStyle = '#e6ebf4'; c.font = F(900, 24); c.fillText(String(tv[i]), x, y + 34); });
      y += 52 + 30;
    }

    // Pie con la marca
    y = Ht - 110;
    c.fillStyle = 'rgba(111,153,202,.08)'; c.fillRect(0, y, W, 110);
    c.textAlign = 'right'; c.fillStyle = '#8391a8'; c.font = F(700, 18);
    c.fillText('DESARROLLADO POR', W / 2 - 20, y + 62);
    if (logo) { const h = 64, w = logo.width * h / logo.height; c.drawImage(logo, W / 2, y + 23, w, h); }
    return cv;
  }

  // ---------- compartir
  function toast(msg) {
    const t = $('toast'); t.textContent = msg; t.style.display = 'block';
    clearTimeout(toast.t); toast.t = setTimeout(() => { t.style.display = 'none'; }, 3500);
  }
  const A = window.HoopsAndroid; // puente de la app Android
  function fileName(ext) {
    const clean = (x) => String(x).normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^\w]+/g, '-').replace(/^-|-$/g, '').slice(0, 30);
    return `${clean(S.home.short)}-${clean(S.away.short)}-${dateText(S).replace(/\//g, '-')}.${ext}`;
  }
  function download(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  }

  $('share-img').addEventListener('click', async () => {
    if (!S) return;
    const cv = await renderImage(S);
    const dataUrl = cv.toDataURL('image/png');
    const name = fileName('png'), text = `${S.home.name} ${S.home.score} – ${S.away.score} ${S.away.name}`;
    if (A && A.shareFile) return A.shareFile(name, 'image/png', dataUrl.split(',')[1], text);
    const blob = await new Promise(r => cv.toBlob(r, 'image/png'));
    const file = window.File ? new File([blob], name, { type: 'image/png' }) : null;
    if (file && navigator.canShare && navigator.canShare({ files: [file] })) {
      try { await navigator.share({ files: [file], text }); return; } catch (e) { if (e.name === 'AbortError') return; }
    }
    // Sin "compartir" del sistema (por ejemplo, desde otro dispositivo por http): vista previa para guardar
    $('ov-img').src = dataUrl;
    $('ov-dl').href = dataUrl;
    $('ov-dl').download = name;
    $('ov').classList.add('on');
  });
  $('ov-close').addEventListener('click', () => $('ov').classList.remove('on'));

  $('share-txt').addEventListener('click', async () => {
    if (!S) return;
    const text = shareText(S);
    if (A && A.shareText) return A.shareText(text);
    if (navigator.share) { try { await navigator.share({ text }); return; } catch (e) { if (e.name === 'AbortError') return; } }
    window.open('https://wa.me/?text=' + encodeURIComponent(text), '_blank');
  });

  $('share-csv').addEventListener('click', () => {
    if (!S) return;
    const text = csv(S), name = fileName('csv');
    if (A && A.shareFile) {
      const b64 = btoa(unescape(encodeURIComponent(text)));
      return A.shareFile(name, 'text/csv', b64, `Planilla ${S.home.short} vs ${S.away.short}`);
    }
    download(new Blob([text], { type: 'text/csv;charset=utf-8' }), name);
    toast('Planilla descargada: se abre con Excel o Google Sheets');
  });

  H.connect(render, () => {}, 'operador');
  setInterval(() => { if (S && S.clock.running) render(S); }, 1000);
})();
