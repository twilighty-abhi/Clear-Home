/* Clear Home — dashboard layout: photo background, clock, weather, info cards
   and a slim app dock. app.js owns focus/navigation; this file owns the data
   and the markup inside #home when layout === 'dashboard'. */
(function () {
  'use strict';

  const native = window.Android;
  const has = (fn) => native && typeof native[fn] === 'function';

  const CONFIG = {
    dream: /[?&]dream\b/.test(location.search) // running as the TV screensaver
  };
  // Settings chosen on the TV (app.js): photo interval, idle delay, units, which cards show.
  const pref = (k, fallback) => {
    const v = window.ClearHome && window.ClearHome.pref ? window.ClearHome.pref(k) : undefined;
    return v === undefined ? fallback : v;
  };

  // Home location from Settings → Location: {name, lat, lon, country, admin1, admin2}, or null.
  let place = null;
  function readPlace() {
    try { place = has('getPlace') ? JSON.parse(native.getPlace()) : null; } catch (e) { place = null; }
    return place;
  }

  // ---------- icons ----------

  const P = (d, cls, delay) => `<path${cls ? ` class="${cls}"` : ''}${delay ? ` style="animation-delay:${delay}s"` : ''} d="${d}"/>`;
  const CLOUD = 'M7 19h10.5a4.5 4.5 0 0 0 .5-8.97A6.5 6.5 0 0 0 5.6 10.2 4.4 4.4 0 0 0 7 19z';
  const CLOUD_HIGH = 'M7 14h10.5a4.5 4.5 0 0 0 .5-8.97A6.5 6.5 0 0 0 5.6 5.2 4.4 4.4 0 0 0 7 14z';
  const CLOUD_SMALL = 'M9 20h8.8a3.7 3.7 0 0 0 .4-7.38A5.3 5.3 0 0 0 8 12.1 3.6 3.6 0 0 0 9 20z';
  // Animated parts carry a-* classes (see app.css): rays turn, clouds drift, rain/snow fall.
  const ICONS = {
    sun: '<g class="a-spin">' + P('M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6') + '</g>' +
      '<circle cx="12" cy="12" r="4.2"/>',
    moon: '<g class="a-bob">' + P('M19.5 14.6A8 8 0 0 1 9.4 4.5a8 8 0 1 0 10.1 10.1z') + '</g>',
    partly: '<g class="a-spin-sm">' + P('M8.5 3v1.4M3.6 5.1l1 1M2 9.6h1.4M13.4 5.1l-1 1') + '</g>' + P('M5.6 11.3a3.4 3.4 0 0 1 6.3-2.6') +
      '<g class="a-drift">' + P(CLOUD_SMALL) + '</g>',
    partlyNight: '<g class="a-bob">' + P('M11 3.6A5 5 0 0 0 4.6 10a5 5 0 0 0 1.4.4') + '</g>' + '<g class="a-drift">' + P(CLOUD_SMALL) + '</g>',
    cloud: '<g class="a-drift">' + P(CLOUD) + '</g>',
    fog: P('M4 9h16', 'a-fog') + P('M3 13h18', 'a-fog', -1.6) + P('M6 17h12', 'a-fog', -3.2),
    drizzle: '<g class="a-drift">' + P('M7 15h10.5a4.5 4.5 0 0 0 .5-8.97A6.5 6.5 0 0 0 5.6 6.2 4.4 4.4 0 0 0 7 15z') + '</g>' +
      P('M9 18.5l-.5 1.5', 'a-drop', 0) + P('M13 18.5l-.5 1.5', 'a-drop', -0.8) + P('M17 18.5l-.5 1.5', 'a-drop', -0.4),
    rain: '<g class="a-drift">' + P(CLOUD_HIGH) + '</g>' +
      P('M8.5 17l-1.2 3.2', 'a-drop', 0) + P('M12.5 17l-1.2 3.2', 'a-drop', -0.5) + P('M16.5 17l-1.2 3.2', 'a-drop', -0.25),
    storm: '<g class="a-drift">' + P(CLOUD_HIGH) + '</g>' + P('M12.5 14l-2 3.6h3l-2 3.9', 'a-flash'),
    snow: '<g class="a-drift">' + P(CLOUD_HIGH) + '</g>' +
      P('M9 18h.01', 'a-flake', 0) + P('M12 20h.01', 'a-flake', -0.8) + P('M15 18h.01', 'a-flake', -1.6),
    car: P('M5 16.5V12l1.8-4.6A2 2 0 0 1 8.7 6h6.6a2 2 0 0 1 1.9 1.4L19 12v4.5') + P('M4 12h16v4.5H4z') + '<circle cx="7.5" cy="14.3" r=".6"/><circle cx="16.5" cy="14.3" r=".6"/>' + P('M6 16.5v2M18 16.5v2'),
    snowflake: P('M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9') + P('M9.5 4.5L12 6.5l2.5-2M9.5 19.5L12 17.5l2.5 2'),
    leaf: P('M5 19c0-8 5-13 14-14-1 9-6 14-14 14z') + P('M5 19l7-7'),
    wifi: P('M2.5 9a14 14 0 0 1 19 0') + P('M5.5 12.4a9.5 9.5 0 0 1 13 0') + P('M8.6 15.7a5 5 0 0 1 6.8 0') + '<circle cx="12" cy="19" r="1.1"/>',
    pin: '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M12 2.5a6.8 6.8 0 0 0-6.8 6.8c0 4.9 6.8 12.2 6.8 12.2s6.8-7.3 6.8-12.2A6.8 6.8 0 0 0 12 2.5zm0 9.3a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z"/>',
    warn: P('M12 3.5l9.5 16.5h-19z') + P('M12 10v4.5') + '<circle cx="12" cy="17.3" r=".6"/>',
    note: P('M5 4h14v11l-5 5H5z') + P('M14 20v-5h5') + P('M8.5 9h7M8.5 12.5h4.5'),
    drop: P('M12 3.5c3.2 4.2 5.5 7.4 5.5 10.3a5.5 5.5 0 0 1-11 0c0-2.9 2.3-6.1 5.5-10.3z'),
    // Wall-mounted AC: body, vent, status light, and air lines that drift down while cooling.
    aircon: '<rect x="2.5" y="4" width="19" height="9" rx="2.2"/>' + P('M5.5 10.2h13') + '<circle class="ac-led" cx="18.3" cy="6.9" r=".55"/>' +
      P('M7.5 15.5q1.2 1.6 0 3.2', 'a-air', 0) + P('M12 15.5q1.2 1.6 0 3.2', 'a-air', -0.5) + P('M16.5 15.5q1.2 1.6 0 3.2', 'a-air', -1)
  };
  const icon = (name, cls) => `<svg class="${cls || 'ico'}" viewBox="0 0 24 24">${ICONS[name] || ''}</svg>`;

  function weatherIcon(code, day) {
    if (code === 0) return day ? 'sun' : 'moon';
    if (code <= 2) return day ? 'partly' : 'partlyNight';
    if (code === 3) return 'cloud';
    if (code === 45 || code === 48) return 'fog';
    if (code >= 51 && code <= 57) return 'drizzle';
    if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return 'rain';
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snow';
    if (code >= 95) return 'storm';
    return 'cloud';
  }

  const CONDITIONS = {
    0: 'Clear', 1: 'Mostly clear', 2: 'Partly cloudy', 3: 'Cloudy', 45: 'Fog', 48: 'Fog',
    51: 'Light drizzle', 53: 'Drizzle', 55: 'Heavy drizzle', 56: 'Freezing drizzle', 57: 'Freezing drizzle',
    61: 'Light rain', 63: 'Rain', 65: 'Heavy rain', 66: 'Freezing rain', 67: 'Freezing rain',
    71: 'Light snow', 73: 'Snow', 75: 'Heavy snow', 77: 'Snow', 80: 'Showers', 81: 'Showers',
    82: 'Heavy showers', 85: 'Snow showers', 86: 'Snow showers', 95: 'Thunderstorm', 96: 'Thunderstorm', 99: 'Thunderstorm'
  };

  function aqiLabel(v) {
    if (v <= 50) return 'Good';
    if (v <= 100) return 'Moderate';
    if (v <= 150) return 'Unhealthy for some';
    if (v <= 200) return 'Unhealthy';
    return 'Very unhealthy';
  }

  // ---------- state ----------

  let weather = null;   // { temp, feels, humidity, code, day, hi, lo, hours: [{label, code, day, temp, rain}] }
  let aqi = null;
  let commute = null;   // { minutes, typical, level: 'light'|'moderate'|'heavy', via, sample }
  let acs = [];         // [{ name, on, watts, kwhToday, kwhMonth, runMinutes, ok, sample }]
  let alerts = [];       // official alerts covering home (SACHET)
  let notes = [];        // notes from flatmates' phones
  let house = null;      // { flatmates:[{name,colour,home}], chores:[{name,assignee,done,every}], tv:{allowed,minutes,top} }
  let rainSlots = null;  // [{t: ms, mm}] 15-minute precipitation forecast
  let photos = [];
  let current = null;    // { p, memory } photo on screen
  const backStack = [];  // previously shown, for ◀
  let slideCount = 0;
  let paused = false;
  let hidePrompt = false;
  let photoFront = 0;
  let photoTimer = 0;
  let idleTimer = 0;
  let mounted = false;

  // ---------- markup ----------

  function html(vis, tileFn, esc) {
    mounted = true;
    const dock = vis.map((a) => tileFn(a, 'app:' + a.pkg)).join('') +
      '<div class="tile gear" tabindex="-1" data-f data-key="ctl:settings" data-action="settings">' +
      '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/></svg></div>';
    return '<div class="photos"><div class="ph"></div><div class="ph"></div></div>' +
      '<div class="shade"></div>' +
      '<section class="now"><div class="d-greet"></div><div class="d-time"></div><div class="d-date"></div>' +
      '<div class="d-home"></div><div class="d-weather"></div><div class="np"></div></section>' +
      '<section class="cards"></section>' +
      '<div class="dock-label"></div>' +
      `<div class="dock"><div class="dock-track">${dock}</div></div>` +
      '<div class="credit"></div>' +
      '<div class="idle-hint">◀ ▶ photos · OK pause · ▼ hide this photo</div>' +
      '<div class="hide-prompt"><b>Hide this photo?</b><span>OK to hide · Back to keep</span></div>';
  }

  function paint() {
    if (!mounted || !document.querySelector('#home .now')) return;
    paintClock();
    magnify();
    paintWeather();
    paintCards();
    loadNowPlaying();
    showPhoto(false);
  }

  function paintClock() {
    const t = document.querySelector('#home .d-time');
    if (!t) return;
    const d = new Date();
    const h = d.getHours();
    const clock24 = window.ClearHome && window.ClearHome.clock24 && window.ClearHome.clock24();
    t.textContent = clock24 ? String(h).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
      : (h % 12 || 12) + ':' + String(d.getMinutes()).padStart(2, '0');
    document.querySelector('#home .d-date').textContent =
      d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
    paintGreeting();
  }

  // Shared TV: a plain greeting (no names). Who's home and today's TV time sit under the date.
  function paintGreeting() {
    const g = document.querySelector('#home .d-greet');
    const homeEl = document.querySelector('#home .d-home');
    if (!g || !homeEl) return;
    const h = new Date().getHours();
    g.textContent = h < 5 ? 'Good night' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : h < 22 ? 'Good evening' : 'Good night';
    const fms = (house && house.flatmates) || [];
    const tv = house && house.tv;
    const tvText = tv && tv.allowed && tv.minutes >= 1
      ? (tv.minutes >= 60 ? Math.floor(tv.minutes / 60) + ' h ' + (tv.minutes % 60) + ' m' : tv.minutes + ' min') + ' of TV today'
      : '';
    homeEl.innerHTML = fms.map((f) =>
      `<span class="hm${f.home ? ' in' : ''}${f.emoji ? ' emo' : ''}" style="--c:${esc(f.colour)}">` +
      `${f.emoji ? esc(f.emoji) : esc(f.initials || (f.name || '?')[0])}</span>`).join('') +
      (tvText ? `<span class="tvt">${tvText}</span>` : '');
    homeEl.classList.toggle('on', fms.length > 0 || !!tvText);
  }

  function paintWeather() {
    const el = document.querySelector('#home .d-weather');
    if (!el) return;
    if (!place) {
      el.innerHTML = '<div class="w-main"><div class="w-text"><div class="w-cond">Weather</div>' +
        '<div class="w-det">Choose your location in Settings → Location</div></div></div>';
      return;
    }
    if (!weather) { el.innerHTML = ''; return; }
    const w = weather;
    const details = [`Feels ${Math.round(w.feels)}°`, `H ${Math.round(w.hi)}°  L ${Math.round(w.lo)}°`];
    if (aqi != null) details.push(`AQI ${Math.round(aqi)} · ${aqiLabel(aqi)}`);
    const hourly = window.ClearHome && window.ClearHome.hourly && window.ClearHome.hourly();
    el.innerHTML =
      '<div class="w-main">' + icon(weatherIcon(w.code, w.day), 'w-ico') +
      `<div class="w-temp">${Math.round(w.temp)}°</div>` +
      `<div class="w-text"><div class="w-cond">${CONDITIONS[w.code] || ''} · ${esc(place.name)}</div>` +
      `<div class="w-det">${details.join('<span class="sep"></span>')}</div>` +
      (rainText() ? `<div class="w-rain">${icon('drop', 'w-drop')}<span>${rainText()}</span></div>` : '') +
      '</div></div>' +
      (!hourly ? '' : '<div class="w-hours">' + w.hours.map((h) =>
        `<div class="w-h"><div class="w-hl">${h.label}</div>${icon(weatherIcon(h.code, h.day), 'w-hi')}` +
        `<div class="w-ht">${Math.round(h.temp)}°</div>` +
        `<div class="w-hr">${h.rain >= 30 ? h.rain + '%' : ''}</div></div>`).join('') + '</div>');
  }

  function paintCards() {
    const el = document.querySelector('#home .cards');
    if (!el) return;
    let out = timersCard();
    alerts.slice(0, 2).forEach((a) => {
      const sev = String(a.severity || 'ALERT').toUpperCase();
      const label = sev === 'WARNING' ? 'Warning' : sev === 'WATCH' ? 'Watch' : sev === 'ALERT' ? 'Alert' : cap(sev.toLowerCase());
      const until = a.until ? fmtTime(a.until) : '';
      // Same shape as the AC card: icon row, the message, a divider, then until/source.
      out += `<div class="card alert keep sev-${sev.toLowerCase()}"><div class="ac-row al-row">${icon('warn', 'ac-ico al-ico')}` +
        `<div class="ac-name">${esc(a.type)}</div><div class="ac-state al-sev">${label}</div>` +
        `<div class="ac-sub al-msg" lang="${esc(a.lang || 'en')}">${esc(a.message)}</div></div>` +
        `<div class="c-sub dim ac-total">${until ? 'Until ' + until + ' · ' : ''}${esc(a.source || 'NDMA')}${a.sample ? ' · sample' : ''}</div></div>`;
    });
    if (commute && !pref('trafficCard', true)) {
      // Traffic card switched off in Settings.
    } else if (commute && commute.mode === 'map') {
      const c = commute;
      const mins = Math.max(0, Math.round((Date.now() - c.updated) / 60000));
      out += '<div class="card map">' +
        `<img src="${esc(c.url)}" alt="" onerror="this.closest('.card').style.display='none'">` +
        `<div class="map-label">${icon('car', 'c-ico')}<span>Traffic near home</span>` +
        `<em>${mins < 1 ? 'live' : mins + ' min ago'}</em></div>` +
        (c.sample ? '<em class="tag-sample map-sample">sample</em>' : '') + '</div>';
    } else if (commute && commute.mode === 'area') {
      const c = commute;
      const slow = c.slowdowns ? ` · ${c.slowdowns} slowdown${c.slowdowns > 1 ? 's' : ''} nearby` : ' · no slowdowns';
      out += `<div class="card"><div class="c-head">${icon('car', 'c-ico')}<span>Traffic near ${esc(c.area)}</span>` +
        (c.sample ? '<em>sample</em>' : '') + '</div>' +
        `<div class="c-val">${cap(c.level)}</div>` +
        `<div class="c-sub"><span class="dot ${c.level}"></span>Roads at ${c.speedPct}% of normal speed</div>` +
        `<div class="c-sub dim">${slow.slice(3)}</div></div>`;
    } else if (commute) {
      const c = commute;
      const delay = c.minutes - c.typical;
      const vsUsual = delay > 2 ? ` · ${delay} min slower than usual` : delay < -2 ? ` · ${-delay} min faster than usual` : ' · as usual';
      out += `<div class="card"><div class="c-head">${icon('car', 'c-ico')}<span>To ${esc(c.to || 'Work')}</span>` +
        (c.sample ? '<em>sample</em>' : '') + '</div>' +
        `<div class="c-val">${c.minutes}<small> min</small></div>` +
        `<div class="c-sub"><span class="dot ${c.level}"></span>${cap(c.level)} traffic${vsUsual}</div>` +
        (c.distanceKm ? `<div class="c-sub dim">${c.distanceKm} km by road</div>` : '') + '</div>';
    }
    if (acs.length && pref('acCard', true)) {
      // One card for all ACs, a row per room, so more plugs can be added without crowding the screen.
      const sample = acs.some((a) => a.sample);
      const totalToday = acs.reduce((t, a) => t + (a.ok ? a.kwhToday : 0), 0);
      // No title: the AC icon on each row already says what this card is.
      out += '<div class="card ac">' +
        acs.map((a) => {
          const room = esc(a.name.replace(/\s+AC$/i, ''));
          if (!a.ok) return '<div class="ac-row">' + icon('aircon', 'ac-ico') +
            `<div class="ac-name">${room}</div><div class="ac-state dim">Unavailable</div></div>`;
          const power = a.watts >= 1000 ? (a.watts / 1000).toFixed(1) + ' kW' : Math.round(a.watts) + ' W';
          // A plug that's on but drawing almost nothing means the AC was switched off with its remote.
          // (The plug's own runtime counts plug-on time, not cooling time, so it isn't shown.)
          const running = a.on && a.watts >= 20;
          const state = !a.on ? 'Off' : running ? 'On · ' + power : 'Standby';
          return `<div class="ac-row${running ? ' running' : ''}">` + icon('aircon', 'ac-ico') +
            `<div class="ac-name">${room}</div>` +
            `<div class="ac-state">${state}</div>` +
            `<div class="ac-sub">${a.kwhToday.toFixed(1)} units today · ${a.kwhMonth.toFixed(0)} this month</div></div>`;
        }).join('') +
        `<div class="c-sub dim ac-total">${totalToday.toFixed(1)} units today in total` +
        (sample ? '<em class="tag-sample">sample</em>' : '') + '</div></div>';
    }
    out += guestCard();
    out += stickyNote();
    if (out === lastCards && el.childElementCount) return; // nothing changed: keep the DOM (and QR) as is
    lastCards = out;
    el.innerHTML = out;
    fitCards(el);
  }

  // Timers set from the phone app: an iOS-style ring per timer. The card is built once per set of
  // timers; a 1-second ticker only moves the rings and numbers.
  let timerTick = 0;
  const RING = 2 * Math.PI * 24;
  function timersCard() {
    const ts = (house && house.timers) || [];
    clearInterval(timerTick);
    if (!ts.length) return '';
    timerTick = setInterval(tickTimers, 1000);
    setTimeout(tickTimers, 0);
    return '<div class="card timers keep">' + ts.map((t) =>
      `<div class="tm" data-id="${esc(t.id)}" data-ends="${t.ends || 0}" data-left="${t.left || 0}" data-dur="${t.duration || 1}" data-paused="${t.paused ? 1 : ''}">` +
      `<svg class="tm-ring" viewBox="0 0 56 56"><circle cx="28" cy="28" r="24" class="tm-track"/><circle cx="28" cy="28" r="24" class="tm-arc" stroke-dasharray="${RING.toFixed(1)}"/></svg>` +
      `<div class="tm-body"><div class="tm-time">--:--</div><div class="tm-label">${esc(t.label || 'Timer')}${t.paused ? ' · paused' : ''}</div></div></div>`).join('') + '</div>';
  }
  function tickTimers() {
    const now = Date.now();
    document.querySelectorAll('#home .tm').forEach((el) => {
      const d = el.dataset, dur = +d.dur || 1;
      const left = d.paused ? +d.left : Math.max(0, +d.ends - now);
      const s = Math.ceil(left / 1000), h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
      el.querySelector('.tm-time').textContent = left === 0 ? 'Done' : (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(sec).padStart(2, '0');
      el.querySelector('.tm-arc').style.strokeDashoffset = (RING * (1 - left / dur)).toFixed(1);
      el.classList.toggle('done', left === 0);
    });
  }

  // Guest Wi-Fi, switched on from the phone app for a few hours: the network, password and a join QR.
  let guestTimer = 0;
  function guestCard() {
    const g = house && house.guest;
    clearTimeout(guestTimer);
    if (!g || !g.on || !(g.until > Date.now())) return '';
    guestTimer = setTimeout(paintCards, g.until - Date.now() + 1000); // hide itself when time's up
    const wifiEsc = (v) => String(v).replace(/([\\;,:"])/g, '\\$1');
    let qr = '';
    if (typeof window.qrcode === 'function') {
      const q = window.qrcode(0, 'M');
      q.addData(`WIFI:T:${g.security === 'nopass' ? 'nopass' : g.security || 'WPA'};S:${wifiEsc(g.ssid)};P:${wifiEsc(g.pass || '')};;`);
      q.make();
      qr = '<div class="gw-qr">' + q.createSvgTag({ cellSize: 4, margin: 0 }) + '</div>';
    }
    // One compact row: Wi-Fi icon, network + password, and the join code on the right.
    return '<div class="card guest keep">' +
      `<div class="gw-body"><div class="gw-head">${icon('wifi', 'ac-ico gw-ico')}<div class="ac-name">${esc(g.ssid)}</div></div>` +
      `<div class="ac-sub">${!g.pass ? 'No password' : g.showPass ? esc(g.pass) : '•'.repeat(Math.min(12, g.pass.length))}</div>` +
      '<div class="gw-s">Scan to join</div></div>' + qr + '</div>';
  }

  // One sticky note for the family board: the notes as handwritten lines, plus the QR to add one.
  // It stays up in the photo-frame view while it has notes.
  let notesUrl = null, qrSvg = '', qrFor = '', lastCards = '';
  function stickyNote() {
    if (notesUrl === null) {
      try { notesUrl = has('notesUrl') ? native.notesUrl() : ''; } catch (e) { notesUrl = ''; }
    }
    const chores = ((house && house.chores) || []).filter((c) => c.assignee);
    const due = ((house && house.reminders) || []).filter((r) => r.daysLeft <= 3);
    if (!notesUrl && !notes.length && !chores.length && !due.length) return '';
    const now = new Date();
    const day = Math.floor((now - new Date(now.getFullYear(), 0, 0)) / 864e5);
    const colour = 'yellow'; // the FigJam yellow, every day
    const tilt = [-1.2, 0.9, -0.6, 1.3, -1][day % 5];
    let qr = '';
    if (notesUrl && typeof window.qrcode === 'function') {
      if (qrFor !== notesUrl) { // make the QR once per address, not on every repaint
        const q = window.qrcode(0, 'M');
        q.addData(notesUrl);
        q.make();
        qrSvg = q.createSvgTag({ cellSize: 3, margin: 0 });
        qrFor = notesUrl;
      }
      qr = '<div class="st-qr">' + qrSvg + '</div>';
    }
    let body = due.length
      ? '<ul class="st-list">' + due.slice(0, 2).map((r) =>
          `<li>${esc(r.title)}<i>${r.daysLeft === 0 ? 'today' : r.daysLeft === 1 ? 'tomorrow' : 'in ' + r.daysLeft + ' days'}</i></li>`).join('') + '</ul>'
      : '';
    body += notes.length
      ? '<ul class="st-list">' + notes.slice(0, chores.length ? 3 : 4).map((n) =>
          `<li>${esc(n.text)}${n.from ? `<i>${esc(n.from)}</i>` : ''}</li>`).join('') + '</ul>'
      : (chores.length || due.length ? '' : '<div class="st-empty">Leave us a note!</div>');
    if (chores.length) {
      body += '<div class="st-chores"><b>Chores</b>' + chores.slice(0, 4).map((c) =>
        `<div class="st-chore${c.done ? ' done' : ''}"><span class="box">${c.done ? '✓' : ''}</span>` +
        `<span>${esc(c.name)}</span> <i>— ${esc(c.assignee)}</i></div>`).join('') + '</div>';
    }
    // Just the QR, tucked into the top-right corner; the notes flow around it.
    return `<div class="card sticky paper-${colour}${notes.length || chores.length || due.length ? ' keep' : ''}" style="--tilt:${tilt}deg">` +
      qr + body + '</div>';
  }

  // The column has room for ~690px of cards; drop the least important ones if needed.
  function fitCards(el) {
    const limit = 1080 - 258 - 34 - el.offsetTop;
    for (const sel of ['.card.map', '.card.ac']) {
      if (el.scrollHeight <= limit) return;
      const c = el.querySelector(sel);
      if (c) c.style.display = 'none';
    }
    // Still too tall: trim the sticky from the bottom (older notes, then chores) rather than run into the apps.
    const lines = [...el.querySelectorAll('.card.sticky .st-chore')].reverse()
      .concat([...el.querySelectorAll('.card.sticky .st-list li')].reverse());
    for (const li of lines) {
      if (el.scrollHeight <= limit) break;
      if (li.parentElement.children.length > 1 || li.classList.contains('st-chore')) li.remove();
    }
    const chores = el.querySelector('.card.sticky .st-chores');
    if (chores && !chores.querySelector('.st-chore')) chores.remove(); // no heading without chores
  }

  // ---------- rain ----------

  function rainText() {
    if (!rainSlots || !rainSlots.length) return '';
    const now = Date.now();
    const wet = (s) => s.mm >= 0.1, heavy = (s) => s.mm >= 2.5;
    const at = fmtTime;
    const i = rainSlots.findIndex((s) => s.t + 15 * 60000 > now);
    if (i < 0) return '';
    const slots = rainSlots.slice(i);
    if (wet(slots[0])) {
      const end = slots.findIndex((s) => !wet(s));
      const kind = slots.slice(0, end < 0 ? slots.length : end).some(heavy) ? 'Heavy rain' : 'Raining';
      return end < 0 ? `${kind} for the next few hours` : `${kind} · easing around ${at(slots[end].t)}`;
    }
    const next = slots.findIndex((s, k) => k <= 8 && wet(s));
    if (next < 0) return '';
    const mins = Math.max(5, Math.round((slots[next].t - now) / 300000) * 5);
    const kind = heavy(slots[next]) ? 'Heavy rain' : 'Rain';
    return mins <= 60 ? `${kind} likely in ~${mins} min` : `${kind} likely around ${at(slots[next].t)}`;
  }

  const esc = (t) => String(t).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
  // Times follow the clock setting (12/24-hour) rather than the browser locale.
  const fmtTime = (ms) => {
    const d = new Date(ms), h = d.getHours(), m = String(d.getMinutes()).padStart(2, '0');
    const clock24 = window.ClearHome && window.ClearHome.clock24 && window.ClearHome.clock24();
    return clock24 ? String(h).padStart(2, '0') + ':' + m : (h % 12 || 12) + ':' + m + (h < 12 ? ' AM' : ' PM');
  };

  // ---------- photos ----------

  // Hidden photos are remembered per TV (by URL) and never shown again until "Unhide all".
  const HIDDEN_KEY = 'clearhome.hiddenPhotos';
  let hidden = new Set();
  try { hidden = new Set(JSON.parse(localStorage.getItem(HIDDEN_KEY)) || []); } catch (e) { /* none */ }
  function saveHidden() { try { localStorage.setItem(HIDDEN_KEY, JSON.stringify(Array.from(hidden))); } catch (e) { /* ignore */ } }

  /** 'day' | 'week' | 'month' if the photo was taken around today's date in an earlier year. */
  function memoryKind(p, now) {
    if (!p.date) return null;
    const d = new Date(p.date);
    if (isNaN(d) || d.getFullYear() >= now.getFullYear()) return null;
    const then = new Date(now.getFullYear(), d.getMonth(), d.getDate());
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const days = Math.abs(then - today) / 864e5;
    if (days < 0.5) return 'day';
    if (days <= 3) return 'week';
    return d.getMonth() === now.getMonth() ? 'month' : null;
  }

  function memoryLabel(entry) {
    if (!entry.memory) return '';
    const years = new Date().getFullYear() - new Date(entry.p.date).getFullYear();
    const ago = years === 1 ? 'A year ago' : years + ' years ago';
    return ago + (entry.memory === 'day' ? ' today' : entry.memory === 'week' ? ' this week' : ' this month');
  }

  // Brighter photos by day, darker ones at night (brightness 0-255 measured on the TV).
  function pickWeighted(list, now) {
    const h = now.getHours();
    const night = h >= 19 || h < 6, dusk = h >= 17 && h < 19;
    const w = list.map((p) => {
      const l = typeof p.lum === 'number' ? p.lum : -1;
      if (l < 0 || dusk) return 1;
      if (night) return l < 90 ? 3 : l < 130 ? 1.5 : 0.6;
      return l > 110 ? 3 : l > 80 ? 1.5 : 0.6;
    });
    let t = w.reduce((a, b) => a + b, 0) * Math.random();
    for (let i = 0; i < list.length; i++) { t -= w[i]; if (t <= 0) return list[i]; }
    return list[list.length - 1];
  }

  function pickPhoto() {
    const now = new Date();
    const pool = photos.filter((p) => !hidden.has(p.url));
    if (!pool.length) return null;
    const recent = new Set(backStack.slice(-Math.floor(pool.length / 2)).map((e) => e.p.url).concat(current ? [current.p.url] : []));
    const fresh = pool.filter((p) => !recent.has(p.url));
    const cands = fresh.length ? fresh : pool;
    slideCount++;
    // "On this day": every other slide, a photo from today's date (then week, then month) in past years.
    if (slideCount % 2 === 0) {
      for (const kind of ['day', 'week', 'month']) {
        const m = cands.filter((p) => memoryKind(p, now) === kind);
        if (m.length) return { p: pickWeighted(m, now), memory: kind };
      }
    }
    return { p: pickWeighted(cands, now), memory: null };
  }

  function showPhoto(advance) {
    if (!photos.length) return;
    const valid = current && !hidden.has(current.p.url) && photos.some((p) => p.url === current.p.url);
    if (!advance && valid) { display(current, false); return; }
    if (current && valid) { backStack.push(current); if (backStack.length > 60) backStack.shift(); }
    const next = pickPhoto();
    if (next) { current = next; display(current, true); }
  }

  function prevPhoto() {
    const prev = backStack.pop();
    if (!prev) return;
    current = prev;
    display(current, true);
    startPhotos();
  }

  function display(entry, force) {
    const layers = document.querySelectorAll('#home .photos .ph');
    if (layers.length < 2) return;
    const p = entry.p;
    if (!force && layers[photoFront].dataset.src === p.url) { setCaption(entry); return; }
    const img = new Image();
    img.onload = () => {
      photoFront = 1 - photoFront;
      const next = layers[photoFront];
      next.dataset.src = p.url;
      next.style.backgroundImage = 'url("' + p.url + '")';
      next.classList.remove('on');
      void next.offsetWidth; // restart the slow zoom
      next.classList.add('on');
      layers[1 - photoFront].classList.remove('on');
      setCaption(entry);
    };
    img.src = p.url;
  }

  function setCaption(entry) {
    const credit = document.querySelector('#home .credit');
    if (!credit) return;
    const p = entry.p;
    // Caption · date · camera in the bottom-left corner; the place alone in the bottom-right.
    let where = credit.parentElement.querySelector('.photo-place');
    if (!where) { where = document.createElement('div'); where.className = 'photo-place'; credit.after(where); }
    where.innerHTML = p.place ? icon('pin', 'cr-pin') + `<span>${esc(p.place)}</span>` : '';
    const text = p.caption || [p.desc || p.title, photoDate(p.date), p.device].filter(Boolean).join(' · ');
    const mem = memoryLabel(entry);
    credit.innerHTML = (mem ? `<b class="memory">${esc(mem)}</b>` : '') + esc(text) +
      (paused ? '<span class="paused">❚❚ Paused</span>' : '');
  }

  // ---------- now playing (Spotify etc. on the TV), shown in the photo-frame view ----------

  let np = {}, npTimer = 0, npEl = null;
  function loadNowPlaying() {
    let next;
    try { next = has('getNowPlaying') ? JSON.parse(native.getNowPlaying()) || {} : {}; } catch (e) { next = {}; }
    const el = document.querySelector('#home .np');
    const key = (o) => [o.title, o.artist, o.art, o.playing, o.duration, o.app].join('|');
    if (el && el === npEl && key(next) === key(np)) { np = next; return; } // same song: the ticker picks up the new timing
    np = next;
    npEl = el;
    paintNowPlaying();
  }
  const clock = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
  function npPosition() { return Math.min(np.duration || 0, (np.position || 0) + (np.playing ? (Date.now() - (np.at || Date.now())) * (np.speed || 1) : 0)); }
  function paintNowPlaying() {
    const el = document.querySelector('#home .np');
    if (!el) return;
    clearInterval(npTimer);
    el.classList.toggle('on', !!np.title);
    if (!np.title) { el.innerHTML = ''; return; }
    el.innerHTML = (np.art ? `<img class="np-art" src="${esc(np.art)}" alt="">` : '<div class="np-art none">♪</div>') +
      `<div class="np-body"><div class="np-title">${esc(np.title)}</div><div class="np-artist">${esc(np.artist || np.album || '')}</div>` +
      (np.duration > 0 ? '<div class="np-bar"><div class="np-played"><i></i></div><b></b></div><div class="np-times"><span class="np-pos"></span><span>' + clock(np.duration) + '</span></div>' : '') +
      `</div><div class="np-app">${np.playing ? '<span class="np-eq"><i></i><i></i><i></i></span>' : '<span class="np-pause">❚❚</span>'}` +
      (np.pkg ? `<img class="np-logo" src="${esc(native.mock ? native.image(np.pkg, 'icon') : '/icon/' + encodeURIComponent(np.pkg))}" alt="${esc(np.app || '')}">` : esc(np.app || '')) + '</div>';
    el.classList.toggle('paused', !np.playing);
    const tick = () => {
      if (!(np.duration > 0)) return;
      const p = npPosition();
      const played = el.querySelector('.np-played'), dot = el.querySelector('.np-bar b'), pos = el.querySelector('.np-pos');
      const f = Math.max(0, Math.min(1, p / np.duration));
      if (played) played.style.width = (f * 100).toFixed(2) + '%';
      if (dot) dot.style.left = (f * 100).toFixed(2) + '%';
      if (pos) pos.textContent = clock(p);
    };
    tick();
    if (np.playing) npTimer = setInterval(tick, 1000);
  }

  // ---------- photo-frame controls (idle view): ◀ ▶ photos · OK pause · ▼ hide ----------

  function isIdle() { return document.getElementById('stage').classList.contains('idle'); }

  function idleKey(k) {
    if (hidePrompt) {
      if (k === 'Enter') {
        if (current) { hidden.add(current.p.url); saveHidden(); }
        closeHidePrompt();
        showPhoto(true);
        if (window.ClearHome) window.ClearHome.toast('Photo hidden · Settings → Photos to unhide');
        return true;
      }
      if (k === 'Escape' || k === 'Backspace' || k === 'BrowserBack' || k === 'GoBack' || k === 'ArrowUp') {
        closeHidePrompt();
        return true;
      }
      return true;
    }
    if (k === 'ArrowRight') { showPhoto(true); startPhotos(); return true; }
    if (k === 'ArrowLeft') { prevPhoto(); return true; }
    if (k === 'Enter') { togglePause(); return true; }
    if (k === 'ArrowDown') { openHidePrompt(); return true; }
    return false; // anything else wakes the home screen
  }

  function togglePause() {
    paused = !paused;
    if (paused) clearInterval(photoTimer); else startPhotos();
    if (current) setCaption(current);
  }

  function openHidePrompt() {
    hidePrompt = true;
    const el = document.querySelector('#home .hide-prompt');
    if (el) el.classList.add('on');
  }

  function closeHidePrompt() {
    hidePrompt = false;
    const el = document.querySelector('#home .hide-prompt');
    if (el) el.classList.remove('on');
  }

  function unhideAll() {
    const n = hidden.size;
    hidden = new Set();
    saveHidden();
    return n;
  }

  function photoDate(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d) ? '' : d.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  }

  function startPhotos() {
    clearInterval(photoTimer);
    if (paused) return;
    photoTimer = setInterval(() => showPhoto(true), pref('photoSeconds', 45) * 1000);
  }

  // ---------- data ----------

  async function loadWeather() {
    if (!readPlace()) { weather = null; aqi = null; rainSlots = null; paintWeather(); return; }
    const { lat, lon } = place;
    // Auto: °F where that's the norm (US and a few others), °C everywhere else.
    const units = pref('units', 'auto');
    const f = units === 'f' || (units === 'auto' && /^(US|BS|BZ|KY|PW|LR|FM|MH)$/.test(place.country || ''));
    const unit = f ? '&temperature_unit=fahrenheit' : '';
    try {
      const r = await fetch('https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon + unit +
        '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day' +
        '&hourly=temperature_2m,weather_code,precipitation_probability,is_day' +
        '&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2' +
        '&minutely_15=precipitation&forecast_minutely_15=12&past_minutely_15=1');
      const j = await r.json();
      const now = j.current.time.slice(0, 13); // "YYYY-MM-DDTHH"
      let start = j.hourly.time.findIndex((t) => t.slice(0, 13) === now) + 1;
      if (start <= 0) start = 0;
      const hours = [];
      for (let i = start; i < start + 6 && i < j.hourly.time.length; i++) {
        const hr = parseInt(j.hourly.time[i].slice(11, 13), 10);
        hours.push({
          label: (hr % 12 || 12) + (hr < 12 ? ' AM' : ' PM'),
          code: j.hourly.weather_code[i], day: j.hourly.is_day[i] === 1,
          temp: j.hourly.temperature_2m[i], rain: j.hourly.precipitation_probability[i] || 0
        });
      }
      const m15 = j.minutely_15;
      rainSlots = m15 && m15.time ? m15.time.map((t, k) => ({ t: new Date(t).getTime(), mm: m15.precipitation[k] || 0 })) : null;
      weather = {
        temp: j.current.temperature_2m, feels: j.current.apparent_temperature,
        humidity: j.current.relative_humidity_2m, code: j.current.weather_code, day: j.current.is_day === 1,
        hi: j.daily.temperature_2m_max[0], lo: j.daily.temperature_2m_min[0], hours: hours
      };
    } catch (e) { console.warn('weather failed: ' + e); }
    try {
      const r = await fetch('https://air-quality-api.open-meteo.com/v1/air-quality?latitude=' + lat +
        '&longitude=' + lon + '&current=us_aqi');
      aqi = (await r.json()).current.us_aqi;
    } catch (e) { /* optional */ }
    paintWeather();
  }

  function loadLocal() {
    const before = JSON.stringify(photos);
    try { if (has('getPhotos')) photos = JSON.parse(native.getPhotos()) || []; } catch (e) { photos = []; }
    // Switched source (e.g. samples -> Google Photos): show the new set straight away.
    if (photos.length && before !== '[]' && (JSON.parse(before)[0] || {}).url !== photos[0].url) current = null;
    try { alerts = has('getAlerts') ? JSON.parse(native.getAlerts()) || [] : []; } catch (e) { alerts = []; }
    try {
      house = has('getHouse') ? JSON.parse(native.getHouse()) : null;
      notes = house ? house.notes || [] : (has('getNotes') ? JSON.parse(native.getNotes()) || [] : []);
    } catch (e) { house = null; notes = []; }
    paintGreeting();
    try { commute = has('getCommute') ? JSON.parse(native.getCommute()) : null; } catch (e) { commute = null; }
    try {
      const a = has('getAc') ? JSON.parse(native.getAc()) : [];
      acs = Array.isArray(a) ? a : (a ? [a] : []);
    } catch (e) { acs = []; }
    paintCards();
    showPhoto(false);
  }

  // ---------- idle: fade to a photo frame ----------

  function wake() {
    const stage = document.getElementById('stage');
    if (CONFIG.dream) { stage.classList.add('idle', 'dream'); return false; } // screensaver: always the photo frame
    const wasIdle = stage.classList.contains('idle');
    stage.classList.remove('idle');
    stage.classList.remove('hint');
    closeHidePrompt();
    clearTimeout(idleTimer);
    const idleSeconds = pref('idleSeconds', 12);
    if (!idleSeconds) return wasIdle; // "Never": the dashboard stays up
    idleTimer = setTimeout(() => {
      if (document.getElementById('stage').dataset.layout !== 'dashboard') return;
      stage.classList.add('idle', 'hint');
      setTimeout(() => stage.classList.remove('hint'), 6000);
    }, idleSeconds * 1000);
    return wasIdle;
  }

  // macOS Dock-style magnification, done entirely with transforms so the TV's GPU animates it:
  // every tile is laid out at full size (288x162) and scaled; positions are computed here.
  const DOCK_GAP = 22, DOCK_VIEW = 1920 - 240, REST = 0.66;
  let dockOffset = 0;
  function magnify() {
    const track = document.querySelector('#home .dock-track');
    if (!track) return;
    const tiles = Array.prototype.slice.call(track.children);
    const at = tiles.indexOf(document.activeElement);
    let x = 0;
    const spans = tiles.map((t, i) => {
      const d = at < 0 ? 99 : Math.abs(i - at);
      const scale = REST + (1 - REST) * Math.exp(-(d * d) / 1.1); // 1, ~0.80, ~0.67, 0.66…
      const w = (t.classList.contains('gear') ? 162 : 288) * scale;
      t.style.transform = `translate3d(${x.toFixed(1)}px,0,0) scale(${scale.toFixed(4)})`;
      const span = [x, w];
      x += w + DOCK_GAP;
      return span;
    });
    // Keep the focused tile (and a bit of what follows) inside the visible row.
    if (at >= 0) {
      const [fx, fw] = spans[at];
      const lead = 140;
      if (fx + dockOffset < 0) dockOffset = -fx;
      else if (fx + fw + lead + dockOffset > DOCK_VIEW) dockOffset = DOCK_VIEW - fx - fw - lead;
      dockOffset = Math.min(0, Math.max(dockOffset, Math.min(0, DOCK_VIEW - x + DOCK_GAP)));
    }
    track.style.transform = `translate3d(${dockOffset.toFixed(1)}px,0,0)`;
  }

  function onFocus(app) {
    magnify();
    const l = document.querySelector('#home .dock-label');
    if (!l) return;
    l.textContent = app ? app.label : (document.activeElement && document.activeElement.dataset.action === 'settings' ? 'Settings' : '');
    l.classList.toggle('on', !!l.textContent);
  }

  function start() {
    wantCards();
    readPlace();
    loadLocal();
    loadWeather();
    startPhotos();
    setInterval(paintClock, 10000);
    setInterval(paintWeather, 60 * 1000); // rain timing text moves on between forecasts
    setInterval(loadWeather, 15 * 60 * 1000);
    setInterval(loadLocal, 60 * 1000);
    setInterval(() => { if (!document.hidden) loadNowPlaying(); }, 4000); // track changes, even if a notice was missed
    wake();
  }

  // Don't poll plugs or traffic for cards that are switched off.
  function wantCards() {
    if (has('wantCards')) native.wantCards(!!pref('acCard', true), !!pref('trafficCard', true));
  }

  // A setting changed on the TV: re-read what depends on it.
  function settingsChanged() {
    wantCards();
    startPhotos();
    wake();
    loadWeather();
    paintCards();
  }

  window.Dashboard = {
    settingsChanged: settingsChanged, placeChanged: loadWeather,
    html: html, paint: paint, start: start, wake: wake, onFocus: onFocus, refresh: () => { loadLocal(); loadNowPlaying(); }, media: loadNowPlaying,
    isIdle: isIdle, idleKey: idleKey, unhideAll: unhideAll, hiddenCount: () => hidden.size,
    // Shared dashboard data (weather, photos, notes) for other views.
    data: () => ({
      weather: weather, condition: weather ? CONDITIONS[weather.code] || '' : '', place: place ? place.name : '',
      rain: rainText(), photos: photos, notes: notes, alerts: alerts, hidden: hidden, memoryKind: memoryKind
    })
  };
})();
