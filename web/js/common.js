/* Funciones compartidas por todas las pantallas.
   Escrito en JavaScript "clásico" para que funcione en navegadores de Smart TV. */
(function (w) {
  'use strict';
  var H = {};
  var offset = 0;          // diferencia entre el reloj del servidor y el de este dispositivo
  var lastBuzz = null;
  var audioCtx = null;
  var lastMsg = 0;
  H.state = null;

  // ---- conexión en tiempo real
  H.connect = function (onState, onStatus) {
    if (!w.EventSource) { alert('Este navegador no soporta EventSource.'); return; }
    var es = new EventSource('/events');
    function status(ok) { if (onStatus) onStatus(ok); }
    es.onmessage = function (e) {
      var s;
      try { s = JSON.parse(e.data); } catch (err) { return; }
      offset = s.now - Date.now();
      lastMsg = Date.now();
      H.state = s;
      status(true);
      onState(s);
    };
    es.onerror = function () { status(false); };
    // El servidor manda el estado cada 5 s; si pasan 12 s sin nada, lo marcamos desconectado.
    setInterval(function () { if (Date.now() - lastMsg > 12000) status(false); }, 3000);
  };

  // ---- relojes
  H.now = function () { return Date.now() + offset; };
  H.rem = function (c) {
    if (!c) return 0;
    return c.running ? Math.max(0, c.remaining - (H.now() - c.startedAt)) : c.remaining;
  };
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function tenths(ms) { var t = Math.floor(ms / 100); return Math.floor(t / 10) + '.' + (t % 10); }
  // Reloj de juego: m:ss, y décimas en el último minuto (como FIBA)
  H.fmtGame = function (ms) {
    if (ms < 60000) return tenths(ms);
    var s = Math.ceil(ms / 1000);
    return Math.floor(s / 60) + ':' + pad(s % 60);
  };
  // Reloj de tiro: segundos enteros, décimas por debajo de 5 s
  H.fmtShot = function (ms) {
    if (ms > 0 && ms < 5000) return tenths(ms);
    return String(Math.ceil(ms / 1000));
  };
  // El reloj de tiro se apaga cuando queda menos tiempo de juego que de posesión
  H.shotVisible = function (s) { return !(H.rem(s.shot) > H.rem(s.clock)); };

  H.periodLabel = function (s) { return s.period <= s.periods ? 'Q' + s.period : 'OT' + (s.period - s.periods); };
  H.periodName = function (s) {
    if (s.period <= s.periods) return 'CUARTO ' + s.period;
    var n = s.period - s.periods;
    return n > 1 ? 'SUPLEMENTARIO ' + n : 'SUPLEMENTARIO';
  };

  H.esc = function (v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };

  // ---- envío de acciones (lo usa el operador)
  H.send = function (type, data) {
    data = data || {};
    data.type = type;
    return fetch('/api/action', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)
    }).then(function (r) { return r.json(); }).then(function (j) {
      if (!j.ok) throw new Error(j.error || 'Error');
      return j;
    });
  };

  // ---- chicharra (Web Audio, sin archivos)
  H.unlockAudio = function () {
    try {
      var A = w.AudioContext || w.webkitAudioContext;
      if (!audioCtx && A) audioCtx = new A();
      if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { /* sin audio */ }
  };
  H.beep = function (sec) {
    if (!audioCtx) return;
    var t = audioCtx.currentTime, dur = sec || 1.4;
    var g = audioCtx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.35, t + 0.03);
    g.gain.setValueAtTime(0.35, t + dur - 0.08);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    g.connect(audioCtx.destination);
    var freqs = [196, 247, 294];
    for (var i = 0; i < freqs.length; i++) {
      var o = audioCtx.createOscillator();
      o.type = 'sawtooth';
      o.frequency.value = freqs[i];
      o.connect(g);
      o.start(t);
      o.stop(t + dur);
    }
  };
  H.checkBuzzer = function (s) {
    if (lastBuzz !== null && s.buzzer > lastBuzz) H.beep();
    lastBuzz = s.buzzer;
  };

  H.fullscreen = function () {
    var d = document.documentElement;
    var f = d.requestFullscreen || d.webkitRequestFullscreen || d.msRequestFullscreen;
    if (!f) return;
    try { var p = f.call(d); if (p && p.catch) p.catch(function () {}); } catch (e) { /* no permitido */ }
  };

  // Capa inicial en las TVs: un toque/OK activa sonido y pantalla completa.
  H.setupOverlay = function (id) {
    var el = document.getElementById(id);
    if (!el) return;
    function go() {
      H.unlockAudio();
      H.fullscreen();
      el.style.display = 'none';
      document.removeEventListener('keydown', go);
    }
    el.addEventListener('click', go);
    document.addEventListener('keydown', go);
    try { el.focus(); } catch (e) { /* nada */ }
    // Un segundo toque en cualquier lado vuelve a pedir pantalla completa
    document.addEventListener('dblclick', function () { H.fullscreen(); });
  };

  w.Hoops = H;
})(window);
