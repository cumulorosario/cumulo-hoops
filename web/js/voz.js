/* Lectura en voz alta del registro del partido.
   - Dentro de la app Android usa la voz del sistema (puente HoopsAndroid).
   - En el navegador de cualquier celular o PC usa la síntesis de voz del navegador.
   Lee las jugadas nuevas en orden; si se acumulan muchas, resume para no atrasarse. */
(function (w) {
  'use strict';
  var H = w.Hoops;
  var A = w.HoopsAndroid;
  var synth = w.speechSynthesis;
  var V = {};
  var KEY = 'hoops-voz';
  var opts = { on: false, rate: 1, tipos: { tantos: true, faltas: true, cambios: true, posesion: true, juego: true } };
  try { var saved = JSON.parse(localStorage.getItem(KEY) || 'null'); if (saved) { opts.on = !!saved.on; opts.rate = saved.rate || 1; for (var k in saved.tipos) opts.tipos[k] = saved.tipos[k]; } } catch (e) { /* sin almacenamiento */ }
  function save() { try { localStorage.setItem(KEY, JSON.stringify(opts)); } catch (e) { /* nada */ } }

  V.available = !!((A && A.speak) || synth);
  V.options = opts;
  V.isOn = function () { return opts.on; };

  var voice = null;
  function pickVoice() {
    if (!synth) return;
    var vs = synth.getVoices() || [];
    voice = vs.filter(function (v) { return /^es[-_]AR/i.test(v.lang); })[0] ||
            vs.filter(function (v) { return /^es[-_](419|US|MX|UY|CL|CO)/i.test(v.lang); })[0] ||
            vs.filter(function (v) { return /^es/i.test(v.lang); })[0] || null;
  }
  if (synth) { pickVoice(); if ('onvoiceschanged' in synth) synth.onvoiceschanged = pickVoice; }

  var queue = [], speaking = false;
  function next() {
    if (!queue.length) { speaking = false; return; }
    speaking = true;
    var text = queue.shift();
    var u = new SpeechSynthesisUtterance(text);
    u.lang = voice ? voice.lang : 'es-AR';
    if (voice) u.voice = voice;
    u.rate = opts.rate;
    u.onend = u.onerror = function () { setTimeout(next, 120); };
    synth.speak(u);
  }

  /** Lee un texto (se encola detrás de lo que se está leyendo). */
  V.say = function (text, force) {
    if (!text || (!opts.on && !force)) return;
    if (A && A.speak) { A.speak(String(text), opts.rate); return; }
    if (!synth) return;
    queue.push(String(text));
    // Si se atrasa mucho (varias jugadas seguidas), quedarse con las últimas
    if (queue.length > 4) queue.splice(0, queue.length - 3, 'Varias jugadas seguidas.');
    if (!speaking) next();
  };
  V.stop = function () {
    queue = [];
    if (A && A.stopSpeaking) A.stopSpeaking();
    if (synth) synth.cancel();
    speaking = false;
  };
  V.setOn = function (on) {
    opts.on = !!on; save();
    if (on) V.say('Lectura en voz alta activada.', true); else V.stop();
  };
  V.setRate = function (r) { opts.rate = r; save(); };
  V.setTipo = function (k, on) { opts.tipos[k] = !!on; save(); };

  /** A qué grupo pertenece una jugada, para que cada uno elija qué escuchar. */
  V.tipo = function (entry) {
    var t = entry.text || '';
    if (/^Falta|falta/.test(t)) return 'faltas';
    if (/^(Cambio [A-Z0-9]{1,4}:|Ingresa|Sale )/.test(t)) return 'cambios';
    if (/^Cambio de posesión/.test(t)) return 'posesion';
    if (/^[A-Z0-9]{1,4} [+−-]\d/.test(t)) return 'tantos';
    return 'juego';
  };

  /** Resumen hablado del partido: marcador, período, tiempo y faltas. */
  V.resumen = function (s) {
    var h = s.home, a = s.away, out = [];
    out.push(h.score === a.score ? 'Empate en ' + h.score + '.'
      : (h.score > a.score ? h.name + ' ' + h.score + ', ' + a.name + ' ' + a.score
        : a.name + ' ' + a.score + ', ' + h.name + ' ' + h.score) + '.');
    var ord = ['primer', 'segundo', 'tercer', 'cuarto'];
    var per = s.period <= s.periods ? (ord[s.period - 1] || s.period + 'º') + ' cuarto'
      : (s.period - s.periods > 1 ? 'suplementario número ' + (s.period - s.periods) : 'suplementario');
    var ms = H.rem(s.clock), sec = Math.ceil(ms / 1000), m = Math.floor(sec / 60), ss = sec % 60;
    var t = m === 0 ? ss + ' segundos' : (ss === 0 ? m + (m === 1 ? ' minuto' : ' minutos') : m + (m === 1 ? ' minuto' : ' minutos') + ' y ' + ss + ' segundos');
    out.push(ms <= 0 ? 'Terminó el ' + per + '.' : 'Quedan ' + t + ' del ' + per + (s.clock.running ? '.' : ', con el reloj detenido.'));
    if (s.possession) out.push('Posesión de ' + s[s.possession].name + '.');
    out.push('Faltas en el cuarto: ' + h.name + ' ' + h.fouls + ', ' + a.name + ' ' + a.fouls + '.');
    return out.join(' ');
  };

  /**
   * Sigue el registro: llamarlo con cada estado nuevo. Lee las jugadas nuevas
   * (según los tipos elegidos) y avisa si se deshizo alguna. onNew(entries) recibe
   * las jugadas nuevas para mostrarlas (por ejemplo, en una región aria-live).
   */
  V.follow = function (onNew) {
    var last = null;
    return function (s) {
      var log = s.log || [];
      var top = log.length ? (log[0].n || 0) : 0;
      if (last === null) { last = top; return; }      // al abrir no se lee el historial
      if (top > last) {
        var nuevas = log.filter(function (e) { return (e.n || 0) > last; }).reverse();
        last = top;
        if (onNew) onNew(nuevas);
        nuevas.forEach(function (e) { if (opts.tipos[V.tipo(e)] !== false) V.say(e.say || e.text); });
      } else if (top < last) {
        last = top;
        if (onNew) onNew([{ say: 'Se deshizo la última jugada. ' + V.resumen(s), text: 'Se deshizo la última jugada' }]);
        V.say('Se deshizo la última jugada. ' + V.resumen(s));
      }
    };
  };

  w.HoopsVoz = V;
})(window);
