/* ТВ Витрина — веб-пульт для iPhone.
   Работает в Safari без установки приложений. */

(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };

  var state = null;
  var ws = null;
  var wsReady = false;
  var seeking = false;
  var pollTimer = null;
  var wakeLock = null;
  var uploading = false;

  // ---------------------------------------------------------------- транспорт

  function connect() {
    try {
      ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    } catch (e) { startPolling(); return; }

    ws.onopen = function () { wsReady = true; stopPolling(); setDot(true); };
    ws.onmessage = function (ev) { try { render(JSON.parse(ev.data)); } catch (e) {} };
    ws.onclose = function () { wsReady = false; setDot(false); startPolling(); setTimeout(connect, 2500); };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(function () {
      fetch('/api/state').then(function (r) { return r.json(); })
        .then(function (s) { setDot(true); render(s); })
        .catch(function () { setDot(false); });
    }, 1500);
  }

  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  function cmd(obj) {
    if (wsReady && ws && ws.readyState === 1) {
      try { ws.send(JSON.stringify(obj)); return; } catch (e) {}
    }
    fetch('/api/cmd', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(obj)
    }).then(function (r) { return r.json(); }).then(render).catch(function () {});
  }

  function setDot(on) { $('dot').className = 'dot' + (on ? ' on' : ''); }

  // ------------------------------------------------------------------ отрисовка

  function fmtTime(ms) {
    if (!ms || ms < 0) ms = 0;
    var s = Math.floor(ms / 1000);
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    var mm = (h > 0 && m < 10 ? '0' : '') + m;
    return (h > 0 ? h + ':' : '') + mm + ':' + (sec < 10 ? '0' : '') + sec;
  }

  function fmtSize(b) {
    if (b > 1073741824) return (b / 1073741824).toFixed(1) + ' ГБ';
    if (b > 1048576) return (b / 1048576).toFixed(0) + ' МБ';
    if (b > 1024) return (b / 1024).toFixed(0) + ' КБ';
    return b + ' Б';
  }

  var lastGridKey = '';

  function render(s) {
    state = s;

    $('storage').textContent = 'Файлов: ' + s.items.length +
      ' · занято ' + fmtSize(s.usedBytes) + ' · свободно ' + fmtSize(s.freeBytes);

    // текущий файл
    var cur = null;
    for (var i = 0; i < s.items.length; i++) if (s.items[i].id === s.currentId) cur = s.items[i];

    if (cur) {
      $('nowCard').hidden = false;
      $('nowThumb').src = '/thumb/' + encodeURIComponent(cur.id);
      $('nowName').textContent = cur.name;
      $('nowSub').textContent = (cur.isVideo ? 'Видео' : 'Фото') + ' · ' + fmtSize(cur.size);
      $('playBtn').textContent = s.playing ? '❚❚' : '▶';
      $('seekWrap').style.display = cur.isVideo && s.duration > 0 ? '' : 'none';
      if (!seeking && s.duration > 0) {
        $('seek').value = String(Math.round(s.position / s.duration * 1000));
        $('tPos').textContent = fmtTime(s.position);
        $('tDur').textContent = fmtTime(s.duration);
      }
    } else {
      $('nowCard').hidden = true;
    }

    setChip($('muteChip'), s.muted, 'Звук выкл.', 'Звук вкл.');
    setChip($('repeatChip'), s.repeatOne, 'Повтор вкл.', 'Повтор выкл.');
    setChip($('slideChip'), s.slideshow, 'Слайдшоу вкл.', 'Слайдшоу выкл.');
    if (String(s.interval) !== $('interval').value) $('interval').value = String(s.interval);

    // сетка
    var key = s.items.map(function (x) { return x.id; }).join('|') + '#' + s.currentId;
    if (key !== lastGridKey) {
      lastGridKey = key;
      buildGrid(s);
    }
    $('emptyHint').hidden = s.items.length > 0;

    if (s.error) toast(s.error);
  }

  function setChip(el, on, onText, offText) {
    el.textContent = on ? onText : offText;
    el.className = 'chip' + (on ? ' active' : '');
  }

  function buildGrid(s) {
    var grid = $('grid');
    grid.innerHTML = '';
    s.items.slice().reverse().forEach(function (it) {
      var tile = document.createElement('div');
      tile.className = 'tile' + (it.id === s.currentId ? ' current' : '');

      var img = document.createElement('img');
      img.loading = 'lazy';
      img.src = '/thumb/' + encodeURIComponent(it.id);
      img.alt = it.name;
      img.onerror = function () { img.style.display = 'none'; };
      tile.appendChild(img);

      var badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = it.isVideo ? '▶ видео' : 'фото';
      tile.appendChild(badge);

      var del = document.createElement('button');
      del.className = 'del';
      del.textContent = '×';
      del.setAttribute('aria-label', 'Удалить');
      del.onclick = function (e) {
        e.stopPropagation();
        cmd({ action: 'delete', id: it.id });
        lastGridKey = '';
      };
      tile.appendChild(del);

      tile.onclick = function () { cmd({ action: 'show', id: it.id }); };
      grid.appendChild(tile);
    });
  }

  var toastTimer = null;
  function toast(text) {
    var t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, 3500);
  }

  // -------------------------------------------------------------------- загрузка

  function requestWakeLock() {
    if (!('wakeLock' in navigator)) return;
    navigator.wakeLock.request('screen').then(function (l) { wakeLock = l; }).catch(function () {});
  }

  function releaseWakeLock() {
    if (wakeLock) { try { wakeLock.release(); } catch (e) {} wakeLock = null; }
  }

  function uploadAll(files) {
    if (!files || !files.length) return;
    if (uploading) { toast('Дождитесь окончания текущей загрузки'); return; }
    uploading = true;
    requestWakeLock();

    var list = Array.prototype.slice.call(files);
    var queue = $('queue');
    queue.innerHTML = '';
    $('queueCard').hidden = false;

    var rows = list.map(function (f) {
      var item = document.createElement('div');
      item.className = 'item';
      item.innerHTML = '<div class="name"></div><div class="track"><div class="fill"></div></div><div class="pct">в очереди</div>';
      item.querySelector('.name').textContent = f.name + ' · ' + fmtSize(f.size);
      queue.appendChild(item);
      return item;
    });

    var firstId = null;
    var idx = 0;

    function next() {
      if (idx >= list.length) {
        uploading = false;
        releaseWakeLock();
        lastGridKey = '';
        if (firstId) cmd({ action: 'show', id: firstId });
        setTimeout(function () { $('queueCard').hidden = true; }, 1200);
        toast('Готово: ' + list.length + ' файл(ов) на телевизоре');
        return;
      }
      var file = list[idx];
      var row = rows[idx];
      var fill = row.querySelector('.fill');
      var pct = row.querySelector('.pct');

      var fd = new FormData();
      fd.append('file', file, file.name);

      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload?show=0');
      xhr.upload.onprogress = function (e) {
        if (!e.lengthComputable) { pct.textContent = 'отправка…'; return; }
        var p = e.loaded / e.total;
        fill.style.width = (p * 100).toFixed(1) + '%';
        pct.textContent = Math.round(p * 100) + '% · ' + fmtSize(e.loaded) + ' из ' + fmtSize(e.total);
      };
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          fill.style.width = '100%';
          pct.textContent = 'готово';
          try {
            var res = JSON.parse(xhr.responseText);
            if (!firstId && res.saved && res.saved.length) firstId = res.saved[0];
            if (res.state) render(res.state);
          } catch (e) {}
        } else {
          pct.textContent = 'ошибка (' + xhr.status + ')';
        }
        idx++;
        next();
      };
      xhr.onerror = function () {
        pct.textContent = 'ошибка сети';
        idx++;
        next();
      };
      xhr.send(fd);
    }

    next();
  }

  // -------------------------------------------------------------------- события

  $('pickMedia').addEventListener('change', function (e) { uploadAll(e.target.files); e.target.value = ''; });
  $('pickFiles').addEventListener('change', function (e) { uploadAll(e.target.files); e.target.value = ''; });

  document.addEventListener('click', function (e) {
    var btn = e.target.closest ? e.target.closest('[data-act]') : null;
    if (!btn) return;
    var act = btn.getAttribute('data-act');
    if (act === 'toggle') cmd({ action: 'toggle' });
    else if (act === 'next') cmd({ action: 'next' });
    else if (act === 'prev') cmd({ action: 'prev' });
    else if (act === 'fwd') cmd({ action: 'seekRel', delta: 10000 });
    else if (act === 'back') cmd({ action: 'seekRel', delta: -10000 });
    else if (act === 'stop') cmd({ action: 'stop' });
    else if (act === 'mute') cmd({ action: 'mute', on: !(state && state.muted) });
    else if (act === 'repeat') cmd({ action: 'repeat', on: !(state && state.repeatOne) });
    else if (act === 'slideshow') cmd({
      action: 'slideshow',
      on: !(state && state.slideshow),
      interval: parseInt($('interval').value, 10) || 6
    });
  });

  $('interval').addEventListener('change', function () {
    cmd({
      action: 'slideshow',
      on: !!(state && state.slideshow),
      interval: parseInt($('interval').value, 10) || 6
    });
  });

  $('clearBtn').addEventListener('click', function () {
    if (confirm('Удалить все файлы с телевизора?')) { cmd({ action: 'clear' }); lastGridKey = ''; }
  });

  var seekEl = $('seek');
  seekEl.addEventListener('input', function () {
    seeking = true;
    if (state && state.duration > 0) {
      $('tPos').textContent = fmtTime(seekEl.value / 1000 * state.duration);
    }
  });
  ['change', 'touchend', 'mouseup'].forEach(function (evt) {
    seekEl.addEventListener(evt, function () {
      if (!seeking) return;
      seeking = false;
      if (state && state.duration > 0) {
        cmd({ action: 'seek', position: Math.round(seekEl.value / 1000 * state.duration) });
      }
    });
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && uploading) requestWakeLock();
  });

  connect();
  startPolling();
})();
