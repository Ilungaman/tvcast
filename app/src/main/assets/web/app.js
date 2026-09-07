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
    ws.onmessage = function (ev) {
      try { render(JSON.parse(ev.data)); }
      catch (e) { reportRenderError(e); }
    };
    ws.onclose = function () { wsReady = false; setDot(false); startPolling(); setTimeout(connect, 2500); };
    ws.onerror = function () { try { ws.close(); } catch (e) {} };
  }

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(function () {
      fetch('/api/state').then(function (r) { return r.json(); })
        .then(function (s) {
          setDot(true);
          try { render(s); } catch (e) { reportRenderError(e); }
        })
        .catch(function () { setDot(false); });
    }, 1500);
  }

  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  // A bug in render() used to fail completely silently (both call sites
  // above only guarded against network/parse errors) -- from the outside
  // that looks exactly like "I entered the PIN and nothing happened", with
  // no way to tell a real problem from a slow network. Surfacing it as a
  // toast at least makes a broken render visible instead of a frozen page.
  var lastRenderErrorAt = 0;
  function reportRenderError(e) {
    var now = Date.now();
    if (now - lastRenderErrorAt < 4000) return;
    lastRenderErrorAt = now;
    toast('Ошибка обновления экрана: ' + (e && e.message ? e.message : e));
  }

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
      $('nowSub').textContent = (cur.isVideo ? 'Видео' : 'Фото') + ' · ' + fmtSize(cur.size) +
        (cur.caption ? ' · подпись: «' + cur.caption + '»' : '');
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
    if (s.transition && s.transition !== $('transition').value) $('transition').value = s.transition;
    if (s.cleanupMode && s.cleanupMode !== $('cleanupMode').value) $('cleanupMode').value = s.cleanupMode;
    if (s.cleanupValue && Number($('cleanupValue').value) !== s.cleanupValue) $('cleanupValue').value = String(s.cleanupValue);
    syncCleanupVisibility();

    setChip($('captionsChip'), s.captionsEnabled, 'Подписи вкл.', 'Подписи выкл.');
    $('captionInput').hidden = !s.captionsEnabled;

    setChip($('musicChip'), s.musicEnabled, 'Музыка вкл.', 'Музыка выкл.');
    if (s.musicCategory && s.musicCategory !== $('musicCategory').value) $('musicCategory').value = s.musicCategory;
    if (s.musicTracks) buildMusicList(s.musicTracks);

    if (s.clockStyle && s.clockStyle !== $('clockStyle').value) $('clockStyle').value = s.clockStyle;
    if (s.clockFontSize && Number($('clockFontSize').value) !== s.clockFontSize) $('clockFontSize').value = String(s.clockFontSize);
    if (s.clockColor && s.clockColor !== $('clockColor').value) $('clockColor').value = s.clockColor;
    if (s.clockMotion && s.clockMotion !== $('clockMotion').value) $('clockMotion').value = s.clockMotion;

    setChip($('weatherChip'), s.weatherEnabled, 'Погода вкл.', 'Погода выкл.');
    if (s.weatherForecastDays && String(s.weatherForecastDays) !== $('weatherDays').value) {
      $('weatherDays').value = String(s.weatherForecastDays);
    }

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

  var MUSIC_CATEGORY_LABELS = { calm: 'Спокойная', upbeat: 'Ритмичная', nature: 'Природа', festive: 'Праздничная' };

  var lastMusicKey = '';
  function buildMusicList(tracksByCategory) {
    var key = JSON.stringify(tracksByCategory);
    if (key === lastMusicKey) return;
    lastMusicKey = key;
    var list = $('musicList');
    list.innerHTML = '';
    Object.keys(tracksByCategory).forEach(function (cat) {
      var tracks = tracksByCategory[cat];
      if (!tracks || !tracks.length) return;
      var head = document.createElement('div');
      head.className = 'hint';
      head.textContent = (MUSIC_CATEGORY_LABELS[cat] || cat) + ':';
      list.appendChild(head);
      tracks.forEach(function (t) {
        var row = document.createElement('div');
        row.className = 'item';
        var name = document.createElement('div');
        name.className = 'name';
        name.textContent = t.name;
        row.appendChild(name);
        var del = document.createElement('button');
        del.className = 'chip danger';
        del.textContent = 'Удалить';
        del.onclick = function () {
          fetch('/api/music/' + encodeURIComponent(cat) + '/' + encodeURIComponent(t.id), { method: 'DELETE' })
            .then(function (r) { return r.json(); }).then(function (s) { lastMusicKey = ''; render(s); }).catch(function () {});
        };
        row.appendChild(del);
        list.appendChild(row);
      });
    });
  }

  function uploadMusic(files) {
    if (!files || !files.length) return;
    var category = $('musicCategory').value;
    Array.prototype.slice.call(files).forEach(function (f) {
      var fd = new FormData();
      fd.append('category', category);
      fd.append('file', f, f.name);
      fetch('/api/music', { method: 'POST', body: fd })
        .then(function (r) { return r.json(); })
        .then(function (s) { lastMusicKey = ''; render(s); toast('Трек добавлен'); })
        .catch(function () { toast('Не удалось загрузить трек'); });
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
    var captionSent = $('captionInput').value.trim();

    function next() {
      if (idx >= list.length) {
        uploading = false;
        releaseWakeLock();
        lastGridKey = '';
        if (firstId) cmd({ action: 'show', id: firstId });
        setTimeout(function () { $('queueCard').hidden = true; }, 1200);
        toast('Готово: ' + list.length + ' файл(ов) на телевизоре' +
          (captionSent ? ' · подпись «' + captionSent + '» сохранена' : ''));
        return;
      }
      var file = list[idx];
      var row = rows[idx];
      var fill = row.querySelector('.fill');
      var pct = row.querySelector('.pct');

      var fd = new FormData();
      // Not gated on $('captionInput').hidden: relying on a "hidden" DOM
      // check to decide whether to send data has already caused one silent
      // bug this session (the PIN gate CSS override) -- simpler and just as
      // correct to send whatever text is actually typed, regardless of the
      // field's current visibility.
      if ($('captionInput').value.trim()) {
        fd.append('caption', $('captionInput').value.trim());
      }
      fd.append('file', file, file.name);

      // A flat XHR timeout would abort a large, slow-but-still-progressing
      // video upload just as readily as a genuinely stuck one. Instead,
      // reset this on every progress tick and only treat it as stuck if
      // NO progress happens for a long stretch -- that's what actually
      // distinguishes "slow" from "hung", and it's the only thing standing
      // between one bad request and the whole queue looking permanently
      // stuck (uploading stays true, so picking more files silently does
      // nothing -- exactly what a stuck request without this looked like).
      var STALL_MS = 45000;
      var stallTimer = null;
      function armStallTimer() {
        clearTimeout(stallTimer);
        stallTimer = setTimeout(function () {
          try { xhr.abort(); } catch (e) {}
        }, STALL_MS);
      }

      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload?show=0');
      armStallTimer();
      xhr.upload.onprogress = function (e) {
        armStallTimer();
        if (!e.lengthComputable) { pct.textContent = 'отправка…'; return; }
        var p = e.loaded / e.total;
        fill.style.width = (p * 100).toFixed(1) + '%';
        pct.textContent = Math.round(p * 100) + '% · ' + fmtSize(e.loaded) + ' из ' + fmtSize(e.total);
      };
      xhr.onload = function () {
        clearTimeout(stallTimer);
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
        clearTimeout(stallTimer);
        pct.textContent = 'ошибка сети';
        idx++;
        next();
      };
      xhr.onabort = function () {
        pct.textContent = 'нет ответа, пропущено';
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
    else if (act === 'captions') cmd({ action: 'captions', on: !(state && state.captionsEnabled) });
    else if (act === 'weather') cmd({
      action: 'weather',
      on: !(state && state.weatherEnabled),
      days: parseInt($('weatherDays').value, 10) || 1
    });
    else if (act === 'music') cmd({
      action: 'music',
      on: !(state && state.musicEnabled),
      category: $('musicCategory').value
    });
  });

  $('musicCategory').addEventListener('change', function () {
    cmd({
      action: 'music',
      on: !!(state && state.musicEnabled),
      category: $('musicCategory').value
    });
  });

  $('pickMusic').addEventListener('change', function (e) { uploadMusic(e.target.files); e.target.value = ''; });

  function sendClockSettings() {
    cmd({
      action: 'clock',
      style: $('clockStyle').value,
      fontSize: parseInt($('clockFontSize').value, 10) || 28,
      color: $('clockColor').value,
      motion: $('clockMotion').value
    });
  }
  $('clockStyle').addEventListener('change', sendClockSettings);
  $('clockFontSize').addEventListener('change', sendClockSettings);
  $('clockColor').addEventListener('change', sendClockSettings);
  $('clockMotion').addEventListener('change', sendClockSettings);

  $('weatherDays').addEventListener('change', function () {
    cmd({
      action: 'weather',
      on: !!(state && state.weatherEnabled),
      days: parseInt($('weatherDays').value, 10) || 1
    });
  });

  $('interval').addEventListener('change', function () {
    cmd({
      action: 'slideshow',
      on: !!(state && state.slideshow),
      interval: parseInt($('interval').value, 10) || 6
    });
  });

  $('transition').addEventListener('change', function () {
    cmd({ action: 'transition', effect: $('transition').value });
  });

  function syncCleanupVisibility() {
    var mode = $('cleanupMode').value;
    $('cleanupValueWrap').hidden = mode === 'off';
    $('cleanupValueLabel').textContent = mode === 'count' ? 'Файлов' : 'Дней';
  }

  function sendCleanup() {
    cmd({
      action: 'cleanup',
      mode: $('cleanupMode').value,
      value: parseInt($('cleanupValue').value, 10) || 30
    });
  }

  $('cleanupMode').addEventListener('change', function () {
    syncCleanupVisibility();
    sendCleanup();
  });
  $('cleanupValue').addEventListener('change', sendCleanup);

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

  // -------------------------------------------------------------------- вход

  // "Nothing happens" after tapping submit is indistinguishable from a slow
  // network unless every outcome is shown -- so every branch below now sets
  // pinError's actual text (the previous version only ever toggled a fixed
  // "wrong PIN" message, silent on a real network/server failure) and the
  // button is disabled/relabeled while the request is in flight so a second
  // tap can't be mistaken for "the first one did nothing".
  function submitPin() {
    var pin = $('pinInput').value.trim();
    var btn = $('pinSubmit');
    btn.disabled = true;
    btn.textContent = 'Проверка…';
    $('pinError').hidden = true;

    // GET with the PIN as a query param instead of POST with a JSON body --
    // removes request-body parsing from the picture entirely. Also guards
    // against the request simply hanging forever with neither a response
    // nor a network error (which would otherwise leave the button stuck on
    // "Проверка…" with no feedback at all -- exactly what "nothing happens"
    // looks like from outside): an 8s timeout via AbortController turns
    // that into a visible, distinct error instead of silence.
    var controller = ('AbortController' in window) ? new AbortController() : null;
    var timedOut = false;
    var timer = setTimeout(function () {
      timedOut = true;
      if (controller) controller.abort();
    }, 8000);

    var opts = { method: 'GET' };
    if (controller) opts.signal = controller.signal;

    fetch('/api/auth?pin=' + encodeURIComponent(pin), opts).then(function (r) {
      clearTimeout(timer);
      if (r.ok) {
        btn.disabled = false;
        btn.textContent = 'Войти';
        $('pinGate').hidden = true;
        $('pinError').hidden = true;
        connect();
        startPolling();
      } else {
        showPinFailure('Сервер ответил: ' + r.status + ' (PIN не подошёл)');
      }
    }).catch(function (e) {
      clearTimeout(timer);
      showPinFailure(timedOut
        ? 'Сервер не ответил за 8 секунд (запрос завис)'
        : 'Ошибка сети: ' + (e && e.message ? e.message : e));
    });

    // The button reverting to a plain "Войти" after a failure looked
    // identical to "nothing happened" -- a small line of text below it is
    // too easy to miss on a phone screen in the same glance. The outcome
    // now shows ON the button itself (stays there, doesn't auto-revert)
    // and pinError is forced to large bold red text via inline styles so
    // there is no way to describe this as silent any more.
    function showPinFailure(message) {
      btn.disabled = false;
      btn.textContent = 'Не удалось — ещё раз';
      var err = $('pinError');
      err.textContent = message;
      err.hidden = false;
      err.style.color = '#ff453a';
      err.style.fontWeight = 'bold';
      err.style.fontSize = '15px';
    }
  }

  // A real form submit (not a click/keydown listener) -- this is what lets
  // the phone keyboard's own "Go" button submit reliably. preventDefault()
  // only stops the browser's own page-navigating submit; submitPin() still
  // runs for every path in (tapping the button, tapping "Go" on the
  // keyboard, or a hardware Enter key).
  $('pinForm').addEventListener('submit', function (e) {
    e.preventDefault();
    submitPin();
  });

  // A cookie from an earlier visit may already be valid -- ask once
  // instead of always showing the gate, so returning visitors on the
  // same phone don't have to re-enter the PIN every time.
  fetch('/api/state').then(function (r) {
    if (r.status === 401) {
      $('pinGate').hidden = false;
    } else {
      connect();
      startPolling();
    }
  }).catch(function () {
    // Network hiccup on the very first load -- fall back to the normal
    // polling/connect path, which will surface the real error itself.
    connect();
    startPolling();
  });
})();
