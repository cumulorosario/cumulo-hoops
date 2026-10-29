(function () {
  'use strict';
  var H = window.Hoops;
  var S = null;
  function $(id) { return document.getElementById(id); }
  function byNum(a, b) { return parseInt(a.num, 10) - parseInt(b.num, 10); }

  function renderTeam(side, t) {
    $(side).style.setProperty('--c', t.color);
    $(side + '-name').textContent = t.name;
    var cr = $(side + '-crest');
    if (cr.getAttribute('data-src') !== (t.logo || '')) {
      cr.setAttribute('data-src', t.logo || '');
      cr.innerHTML = t.logo ? '<img src="' + H.esc(t.logo) + '" alt="">' : '';
      cr.className = 'crest' + (t.logo ? ' on' : '');
    }
    $(side + '-score').textContent = t.score;
    $(side + '-fouls').textContent = t.fouls;
    $(side + '-to').textContent = t.timeouts;
    $(side + '-bonus').style.visibility = t.fouls >= 4 ? 'visible' : 'hidden';

    var on = t.players.filter(function (p) { return p.onCourt; }).sort(byNum);
    var html = '';
    for (var i = 0; i < on.length; i++) {
      var p = on[i], dots = '';
      for (var k = 0; k < 5; k++) dots += '<i class="' + (k < p.fouls ? (p.fouls >= 5 ? 'f x' : 'f') : '') + '"></i>';
      var ph = p.photo ? '<img class="ph" src="' + H.esc(p.photo) + '" alt="">' : '';
      html += '<div class="chip">' + ph + '<b>' + H.esc(p.num) + '</b><span class="nm">' + H.esc(p.name) + '</span><span class="dots">' + dots + '</span></div>';
    }
    var el = $(side + '-players');
    el.style.setProperty('--c', t.color);
    el.innerHTML = html;
  }

  function render(s) {
    S = s;
    $('title').textContent = s.title;
    renderTeam('home', s.home);
    renderTeam('away', s.away);
    $('period').textContent = H.periodName(s);
    document.documentElement.style.setProperty('--hc', s.home.color);
    document.documentElement.style.setProperty('--ac', s.away.color);
    $('arrow-home').className = 'arrow' + (s.possession === 'home' ? ' on' : '');
    $('arrow-away').className = 'arrow' + (s.possession === 'away' ? ' on' : '');
    H.checkBuzzer(s);
    tick();
  }

  var lastClock = '', lastShot = '';
  function tick() {
    if (!S) return;
    var g = H.rem(S.clock), sh = H.rem(S.shot);
    var ct = H.fmtGame(g);
    if (ct !== lastClock) { $('clock').textContent = ct; lastClock = ct; }
    $('clock').className = 'clock' + (S.clock.running ? '' : ' paused');
    var vis = H.shotVisible(S);
    var st = vis ? H.fmtShot(sh) : '';
    if (st !== lastShot) { $('shot').innerHTML = st || '&nbsp;'; lastShot = st; }
    $('shot').className = 'shot' + (vis && sh < 5000 ? ' low' : '') + (vis && sh <= 0 ? ' zero' : '');
  }

  setInterval(tick, 50);
  H.setupOverlay('overlay');
  H.connect(render, function (ok) { $('conn').style.display = ok ? 'none' : 'block'; }, 'tablero');
})();
