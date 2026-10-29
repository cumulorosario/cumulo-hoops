/* Fotos de escudos y jugadores: elegir (cámara o galería), recortar y subir.
   La imagen se achica en el dispositivo antes de enviarla, así viaja rápido por el WiFi. */
(function (w) {
  'use strict';
  var H = w.Hoops;
  var VIEW = 280;            // tamaño del recuadro de recorte en pantalla
  var OUT = { crest: 400, player: 480 };

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  /** Abre el selector del sistema (en el celular ofrece cámara o galería). Devuelve una promesa con el archivo. */
  function chooseFile() {
    return new Promise(function (resolve, reject) {
      var input = el('input');
      input.type = 'file';
      input.accept = 'image/*';
      input.style.display = 'none';
      document.body.appendChild(input);
      input.addEventListener('change', function () {
        var f = input.files && input.files[0];
        document.body.removeChild(input);
        if (f) resolve(f); else reject(new Error('cancelado'));
      });
      input.click();
    });
  }

  function loadImage(file) {
    return new Promise(function (resolve, reject) {
      var url = URL.createObjectURL(file);
      var img = new Image();
      img.onload = function () { resolve(img); };
      img.onerror = function () { URL.revokeObjectURL(url); reject(new Error('No se pudo leer la imagen')); };
      img.src = url;
    });
  }

  /** Recortador: arrastrar para mover, deslizar para acercar, botón para rotar. */
  function crop(img, mode, title) {
    return new Promise(function (resolve, reject) {
      var round = mode === 'player';
      // Es un <dialog> modal para quedar por encima de otros diálogos abiertos (equipo, jugador)
      var ov = el('dialog', 'ph-dlg');
      var box = el('div', 'ph-box');
      box.innerHTML =
        '<div class="ph-title"></div>' +
        '<div class="ph-stage' + (round ? ' round' : '') + '"><canvas width="' + VIEW + '" height="' + VIEW + '"></canvas></div>' +
        '<div class="ph-hint">Arrastrá para encuadrar · usá la barra para acercar</div>' +
        '<div class="ph-row"><span>−</span><input type="range" min="0" max="100" value="0"><span>+</span></div>' +
        '<div class="ph-actions"><button type="button" data-k="rot">⟳ Rotar</button><span></span>' +
        '<button type="button" data-k="no">Cancelar</button><button type="button" class="primary" data-k="ok">Usar foto</button></div>';
      box.querySelector('.ph-title').textContent = title || 'Foto';
      ov.appendChild(box);
      document.body.appendChild(ov);
      ov.addEventListener('cancel', function (e) { e.preventDefault(); close(); reject(new Error('cancelado')); });
      if (ov.showModal) ov.showModal(); else ov.setAttribute('open', '');

      var cv = box.querySelector('canvas'), ctx = cv.getContext('2d');
      var zoom = box.querySelector('input');
      var rot = 0, scale = 1, minScale = 1, cx = 0, cy = 0; // cx, cy: centro de la imagen en el recuadro

      function dims() { return rot % 180 === 0 ? [img.naturalWidth, img.naturalHeight] : [img.naturalHeight, img.naturalWidth]; }
      function fit() {
        var d = dims();
        var cover = VIEW / Math.min(d[0], d[1]);
        var contain = VIEW / Math.max(d[0], d[1]);
        // El escudo entra completo; la foto del jugador llena el círculo
        minScale = mode === 'crest' ? contain : cover;
        scale = minScale;
        zoom.value = 0;
        cx = VIEW / 2; cy = VIEW / 2;
        if (round && d[1] > d[0]) cy = VIEW / 2 + (d[1] * scale - VIEW) * 0.3; // retratos: priorizar la cara
        clamp();
      }
      function clamp() {
        var d = dims(), wv = d[0] * scale, hv = d[1] * scale;
        if (wv >= VIEW) cx = Math.min(wv / 2, Math.max(VIEW - wv / 2, cx)); else cx = VIEW / 2;
        if (hv >= VIEW) cy = Math.min(hv / 2, Math.max(VIEW - hv / 2, cy)); else cy = VIEW / 2;
      }
      function paint(c, size, bg) {
        var k = size / VIEW;
        c.save();
        c.clearRect(0, 0, size, size);
        if (bg) { c.fillStyle = bg; c.fillRect(0, 0, size, size); }
        c.translate(cx * k, cy * k);
        c.rotate(rot * Math.PI / 180);
        c.scale(scale * k, scale * k);
        c.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
        c.restore();
      }
      function draw() { paint(ctx, VIEW, mode === 'crest' ? null : '#111827'); }

      zoom.addEventListener('input', function () {
        var old = scale;
        scale = minScale * Math.pow(4, zoom.value / 100);
        cx = VIEW / 2 + (cx - VIEW / 2) * scale / old;
        cy = VIEW / 2 + (cy - VIEW / 2) * scale / old;
        clamp(); draw();
      });
      var drag = null;
      cv.addEventListener('pointerdown', function (e) { drag = [e.clientX, e.clientY]; cv.setPointerCapture(e.pointerId); });
      cv.addEventListener('pointermove', function (e) {
        if (!drag) return;
        var r = cv.getBoundingClientRect(), k = VIEW / r.width;
        cx += (e.clientX - drag[0]) * k; cy += (e.clientY - drag[1]) * k;
        drag = [e.clientX, e.clientY];
        clamp(); draw();
      });
      cv.addEventListener('pointerup', function () { drag = null; });
      cv.addEventListener('pointercancel', function () { drag = null; });

      function close() {
        if (ov.close) try { ov.close(); } catch (e) { /* ya cerrado */ }
        if (ov.parentNode) ov.parentNode.removeChild(ov);
        URL.revokeObjectURL(img.src);
      }
      box.addEventListener('click', function (e) {
        var k = e.target.getAttribute('data-k');
        if (k === 'rot') { rot = (rot + 90) % 360; fit(); draw(); }
        if (k === 'no') { close(); reject(new Error('cancelado')); }
        if (k === 'ok') {
          var size = OUT[mode] || 400;
          var out = el('canvas'); out.width = size; out.height = size;
          // Escudo en PNG (conserva el fondo transparente); jugador en JPG (más liviano)
          paint(out.getContext('2d'), size, mode === 'crest' ? null : '#111827');
          var type = mode === 'crest' ? 'image/png' : 'image/jpeg';
          out.toBlob(function (blob) {
            close();
            if (blob) resolve(blob); else reject(new Error('No se pudo procesar la imagen'));
          }, type, 0.86);
        }
      });
      fit(); draw();
    });
  }

  /**
   * Flujo completo: elegir → recortar → subir.
   * opts: { team: 'home'|'away', playerId?: number, mode: 'crest'|'player', title }
   */
  H.pickPhoto = function (opts) {
    return chooseFile()
      .then(loadImage)
      .then(function (img) { return crop(img, opts.mode, opts.title); })
      .then(function (blob) {
        var url = '/api/upload?team=' + encodeURIComponent(opts.team) + (opts.playerId != null ? '&player=' + opts.playerId : '');
        return H.post(url, blob, blob.type);
      });
  };
})(window);
