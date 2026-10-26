/* Clear Home — the home screen.
   Talks to the Android side through window.Android (see MainActivity.Bridge);
   in a desktop browser, mock.js stands in for it. */
(function () {
  'use strict';

  const native = window.Android;
  const STORE = 'clearhome.v1';

  const LAYOUTS = [['dashboard', 'Dashboard'], ['shelf', 'Shelf'], ['list', 'List'], ['dock', 'Dock'], ['grid', 'Grid']];

  const BACKGROUNDS = [
    ['dusk', 'Dusk', 'radial-gradient(110% 90% at 85% 5%, #6a4374 0%, rgba(106,67,116,0) 55%), radial-gradient(90% 80% at 0% 100%, #1d3a5f 0%, rgba(29,58,95,0) 60%), #121327'],
    ['dawn', 'Dawn', 'radial-gradient(100% 90% at 90% 0%, #c97b63 0%, rgba(201,123,99,0) 55%), radial-gradient(90% 90% at 0% 100%, #5a3b5d 0%, rgba(90,59,93,0) 60%), #2a1d2b'],
    ['forest', 'Forest', 'radial-gradient(100% 90% at 80% 0%, #3f6b4f 0%, rgba(63,107,79,0) 55%), radial-gradient(90% 90% at 0% 100%, #1a3326 0%, rgba(26,51,38,0) 60%), #0d1a14'],
    ['ocean', 'Ocean', 'radial-gradient(100% 90% at 85% 0%, #2b7a8a 0%, rgba(43,122,138,0) 55%), radial-gradient(90% 90% at 0% 100%, #142d55 0%, rgba(20,45,85,0) 60%), #0a1426'],
    ['ember', 'Ember', 'radial-gradient(100% 90% at 85% 5%, #9a3b22 0%, rgba(154,59,34,0) 55%), radial-gradient(90% 90% at 0% 100%, #3b1420 0%, rgba(59,20,32,0) 60%), #170b0e'],
    ['graphite', 'Graphite', 'radial-gradient(110% 90% at 80% 0%, #4a4d55 0%, rgba(74,77,85,0) 55%), #17181b'],
    ['black', 'Black', '#000']
  ];

  const FONTS = [
    ['outfit', 'Outfit', 'Outfit, Roboto, sans-serif', 400, 300],
    ['light', 'Roboto Light', 'Roboto, "Segoe UI", system-ui, sans-serif', 300, 200],
    ['regular', 'Roboto', 'Roboto, "Segoe UI", system-ui, sans-serif', 400, 300],
    ['condensed', 'Condensed', '"sans-serif-condensed", "Roboto Condensed", "Arial Narrow", sans-serif', 300, 300],
    ['serif', 'Serif', '"Noto Serif", "Droid Serif", Georgia, serif', 400, 400],
    ['mono', 'Mono', '"Droid Sans Mono", "Roboto Mono", Menlo, monospace', 400, 300]
  ];

  // Streaming apps float to the top the first time the app list is built.
  const PREFERRED = [
    'com.netflix.ninja', 'com.google.android.youtube.tv', 'com.amazon.amazonvideo.livingroom',
    'com.disney.disneyplus', 'com.wbd.stream', 'com.hbo.hbonow', 'com.apple.atve.androidtv.appletv',
    'com.peacocktv.peacockandroid', 'com.cbs.ott', 'com.hulu.livingroomplus', 'com.plexapp.android',
    'org.jellyfin.androidtv', 'com.spotify.tv.android', 'com.google.android.youtube.tvmusic',
    'tv.twitch.android.app'
  ];

  // System and utility apps that start hidden (still reachable from All apps).
  const SYSTEM = [
    'com.android.tv.settings', 'com.android.vending', 'com.google.android.play.games',
    'com.google.android.videos', 'com.internet.tvbrowser'
  ];
  const KEEP_TCL = ['com.tcl.tv']; // TCL's live TV / inputs app
  const isSystem = (p) => SYSTEM.indexOf(p) >= 0 || (p.indexOf('com.tcl.') === 0 && KEEP_TCL.indexOf(p) < 0);
  const SEED_VERSION = 4;

  const DEFAULTS = {
    layout: 'dashboard', bg: 'graphite', font: 'outfit', icons: true, clock24: false, hourly: false,
    units: 'auto', photoSeconds: 45, idleSeconds: 12, trafficCard: true, acCard: true,
    order: [], hidden: [], seeded: false, seedVersion: 0
  };

  const SETTINGS = [
    { key: 'layout', label: 'Layout', options: LAYOUTS },
    { key: 'bg', label: 'Background', options: BACKGROUNDS },
    { key: 'font', label: 'Typography', options: FONTS },
    { key: 'icons', label: 'App artwork', options: [[true, 'On'], [false, 'Off']] },
    { key: 'clock24', label: 'Clock', options: [[false, '12-hour'], [true, '24-hour']] },
    { key: 'hourly', label: 'Hourly forecast', options: [[false, 'Off'], [true, 'On']] },
    { key: 'units', label: 'Temperature', options: [['auto', 'Auto'], ['c', '°C'], ['f', '°F']] },
    { key: 'photoSeconds', label: 'Next photo every', options: [[20, '20 seconds'], [45, '45 seconds'], [90, '90 seconds'], [300, '5 minutes']] },
    { key: 'idleSeconds', label: 'Photo frame after', options: [[12, '12 seconds'], [30, '30 seconds'], [60, '1 minute'], [0, 'Never']] },
    { key: 'trafficCard', label: 'Traffic card', options: [[true, 'On'], [false, 'Off']] },
    { key: 'acCard', label: 'Air conditioner card', options: [[true, 'On'], [false, 'Off']] }
  ];

  const ICON_GRID = '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><rect x="14" y="14" width="6" height="6" rx="1"/></svg>';
  const ICON_GEAR = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/></svg>';

  const $ = (id) => document.getElementById(id);
  const stage = $('stage');

  let state = load();
  if (!LAYOUTS.some((l) => l[0] === state.layout)) state.layout = 'dashboard';
  if (/[?&]dream\b/.test(location.search)) state.layout = 'dashboard'; // screensaver view
  let apps = [];
  let byPkg = {};
  let screen = 'home';      // home | settings | edit | all
  const lastFocus = {};     // screen -> data-key
  let moving = null;        // package being reordered in the edit screen
  let resetArmed = false;
  let photoStatus = null;   // last status from the Google Photos bridge
  let photoPoll = 0;
  let disconnectArmed = false;
  let loc = { query: '', searched: '', results: [], busy: '', error: '' }; // Location screen

  // ---------- persistence ----------

  function load() {
    let saved = {};
    try { saved = JSON.parse(localStorage.getItem(STORE)) || {}; } catch (e) { /* first run */ }
    const s = {};
    for (const k in DEFAULTS) s[k] = k in saved ? saved[k] : JSON.parse(JSON.stringify(DEFAULTS[k]));
    return s;
  }

  function save() {
    try { localStorage.setItem(STORE, JSON.stringify(state)); } catch (e) { /* storage unavailable */ }
  }

  // ---------- apps ----------

  function refreshApps() {
    let list = [];
    try { list = JSON.parse(native.getApps()) || []; } catch (e) { list = []; }
    apps = list;
    byPkg = {};
    list.forEach((a) => { byPkg[a.pkg] = a; });

    if (!state.seeded && list.length) {
      const rank = (p) => { const i = PREFERRED.indexOf(p); return i < 0 ? 999 : i; };
      const tv = list.filter((a) => a.tv).sort((a, b) => rank(a.pkg) - rank(b.pkg) || byLabel(a, b));
      const other = list.filter((a) => !a.tv).sort(byLabel);
      state.order = tv.concat(other).map((a) => a.pkg);
      state.hidden = list.filter((a) => !a.tv || isSystem(a.pkg)).map((a) => a.pkg);
      state.seeded = true;
      state.seedVersion = SEED_VERSION;
    } else {
      list.forEach((a) => {
        if (state.order.indexOf(a.pkg) < 0) {
          state.order.push(a.pkg);
          if (!a.tv || isSystem(a.pkg)) state.hidden.push(a.pkg);
        }
      });
      if (state.seeded && state.seedVersion < SEED_VERSION) {
        // One-time upgrade from the first build: hide system apps it showed.
        list.forEach((a) => { if (isSystem(a.pkg) && state.hidden.indexOf(a.pkg) < 0) state.hidden.push(a.pkg); });
        if (state.seedVersion < 4) { state.layout = 'dashboard'; state.font = 'outfit'; } // photo dashboard redesign
        state.seedVersion = SEED_VERSION;
      }
    }
    save();
    render();
  }

  function byLabel(a, b) { return a.label.localeCompare(b.label); }
  const installedOrder = () => state.order.filter((p) => byPkg[p]);
  const visibleApps = () => installedOrder().filter((p) => state.hidden.indexOf(p) < 0).map((p) => byPkg[p]);

  function img(app, kind) {
    if (native.mock) return native.image(app.pkg, kind);
    return '/' + kind + '/' + encodeURIComponent(app.pkg) + '?v=' + app.v;
  }

  function hue(pkg) {
    let h = 0;
    for (let i = 0; i < pkg.length; i++) h = (h * 31 + pkg.charCodeAt(i)) % 360;
    return h;
  }

  function launch(pkg) {
    const a = byPkg[pkg];
    if (!a) return;
    native.launch(pkg);
  }

  // ---------- rendering ----------

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function tile(a, key, opts) {
    opts = opts || {};
    const focusable = opts.focusable === false ? '' : ` tabindex="-1" data-f data-key="${esc(key)}" data-pkg="${esc(a.pkg)}"`;
    let inner;
    if (state.icons && a.banner) {
      inner = `<img class="banner" src="${img(a, 'banner')}" alt="">`;
    } else if (state.icons) {
      inner = `<div class="mark"><img src="${img(a, 'icon')}" alt=""><span>${esc(a.label)}</span></div>`;
    } else {
      inner = `<div class="mark"><span>${esc(a.label)}</span></div>`;
    }
    return `<div class="tile" style="--h:${hue(a.pkg)}"${focusable}>${inner}</div>`;
  }

  function controls() {
    return '<div class="controls">' +
      `<div class="pill" tabindex="-1" data-f data-key="ctl:all" data-action="all">${ICON_GRID}All apps</div>` +
      `<div class="pill" tabindex="-1" data-f data-key="ctl:settings" data-action="settings">${ICON_GEAR}Settings</div>` +
      '</div>';
  }

  function renderHome() {
    const home = $('home');
    const vis = visibleApps();
    home.className = 'layout-' + state.layout + (state.icons ? ' icons' : ' noicons');
    let html = '';

    stage.dataset.layout = state.layout;


    if (state.layout === 'dashboard') {
      home.innerHTML = window.Dashboard.html(vis, tile, esc);
      window.Dashboard.paint();
      return;
    }

    if (state.layout === 'shelf') {
      // Apple TV style: colour wash from the selected app, rows of lifted tiles,
      // names only under the selected tile, Settings as the last tile.
      const settingsTile =
        '<div class="app" tabindex="-1" data-f data-key="ctl:settings" data-action="settings">' +
        `<div class="tile gear-tile">${ICON_GEAR}</div><div class="cap">Settings</div></div>`;
      home.innerHTML =
        '<div class="wash"><div class="w"></div><div class="w"></div></div>' +
        '<div class="shelf" data-scroll="shelf"><div class="shelf-in">' +
        vis.map((a) =>
          `<div class="app" tabindex="-1" data-f data-key="app:${esc(a.pkg)}" data-pkg="${esc(a.pkg)}">` +
          tile(a, '', { focusable: false }) + `<div class="cap">${esc(a.label)}</div></div>`).join('') +
        settingsTile + '</div></div>';
      washKey = null;
      return;
    }

    if (!vis.length) {
      html = `<div class="empty">${apps.length ? 'No apps are shown. Open Settings → Choose &amp; order apps.' : 'Looking for apps…'}</div>`;
    } else if (state.layout === 'list') {
      html = '<div class="list" data-scroll="y">' + vis.map((a) =>
        `<div class="item" tabindex="-1" data-f data-key="app:${esc(a.pkg)}" data-pkg="${esc(a.pkg)}">` +
        (state.icons ? `<img class="ic" src="${img(a, 'icon')}" alt="">` : '') +
        `<span>${esc(a.label)}</span></div>`).join('') + '</div>' +
        '<div class="preview"></div>';
    } else if (state.layout === 'dock') {
      html = '<div class="row" data-scroll="x">' + vis.map((a) => tile(a, 'app:' + a.pkg)).join('') + '</div>' +
        '<div class="dock-name"></div>';
    } else {
      html = '<div class="grid" data-scroll="y">' + vis.map((a) => tile(a, 'app:' + a.pkg)).join('') + '</div>';
    }
    home.innerHTML = html + controls();
  }

  function renderOverlay() {
    const ov = $('overlay');
    ov.className = screen === 'home' ? '' : 'open';
    if (screen === 'settings') ov.innerHTML = settingsHtml();
    else if (screen === 'edit') ov.innerHTML = editHtml();
    else if (screen === 'all') ov.innerHTML = allHtml();
    else if (screen === 'photos') ov.innerHTML = photosHtml();
    else if (screen === 'notes') ov.innerHTML = notesHtml();
    else if (screen === 'location') ov.innerHTML = locationHtml();
    else ov.innerHTML = '';
  }

  function optionLabel(def) {
    const o = def.options.find((x) => x[0] === state[def.key]);
    return o ? o[1] : '';
  }

  function settingsHtml() {
    let isHome = false;
    try { isHome = native.isDefaultHome(); } catch (e) { /* older bridge */ }
    let version = '';
    try { version = native.version(); } catch (e) { /* older bridge */ }
    const rows = SETTINGS.map((d) =>
      `<div class="srow" tabindex="-1" data-f data-key="set:${d.key}" data-setting="${d.key}">` +
      `<span>${d.label}</span><span class="val">${esc(optionLabel(d))}</span></div>`).join('');
    return '<div class="scrim"></div><div class="panel"><h2>Settings</h2><div class="rows" data-scroll="y">' +
      rows +
      `<div class="srow" tabindex="-1" data-f data-key="act:location" data-action="location"><span>Location</span><span class="val">${esc(placeLabel(readPlace()) || 'Not set')} ›</span></div>` +
      `<div class="srow" tabindex="-1" data-f data-key="act:photos" data-action="photos"><span>Photos</span><span class="val">${esc(photosLabel())} ›</span></div>` +
      '<div class="srow" tabindex="-1" data-f data-key="act:notes" data-action="notes"><span>Flat app</span><span class="val">Notes, chores, who\'s home ›</span></div>' +
      '<div class="divider"></div>' +
      '<div class="srow" tabindex="-1" data-f data-key="act:all" data-action="all"><span>All apps</span><span class="val">›</span></div>' +
      '<div class="srow" tabindex="-1" data-f data-key="act:edit" data-action="edit"><span>Choose &amp; order apps</span><span class="val">›</span></div>' +
      '<div class="srow" tabindex="-1" data-f data-key="act:tvsettings" data-action="tvsettings"><span>TV settings</span><span class="val">›</span></div>' +
      `<div class="srow danger" tabindex="-1" data-f data-key="act:reset" data-action="reset"><span>Reset to defaults</span><span class="val">${resetArmed ? 'Press OK again' : ''}</span></div>` +
      `<div class="info">Home button: ${isHome ? 'opens Clear Home' : 'opens the stock launcher'}<br>` +
      `◀ ▶ change a value · Back to close${version ? ' · v' + esc(version) : ''}</div>` +
      '</div></div>';
  }

  function editHtml() {
    const rows = installedOrder().map((p) => {
      const a = byPkg[p];
      const hidden = state.hidden.indexOf(p) >= 0;
      return `<div class="erow${moving === p ? ' moving' : ''}" data-pkg="${esc(p)}">` +
        `<div class="srow${hidden ? ' hidden-app' : ''}" tabindex="-1" data-f data-key="vis:${esc(p)}" data-action="toggle">` +
        (state.icons ? `<img src="${img(a, 'icon')}" alt="">` : '') +
        `<span class="lbl">${esc(a.label)}</span>` +
        (a.tv ? '' : '<span class="tag">phone app</span>') +
        `<span class="val">${hidden ? 'Hidden' : 'Shown'}</span></div>` +
        `<div class="srow mv" tabindex="-1" data-f data-key="mv:${esc(p)}" data-action="move">${moving === p ? 'Done' : 'Move'}</div>` +
        '</div>';
    }).join('');
    return '<div class="scrim"></div><div class="sheet"><h2>Choose &amp; order apps</h2>' +
      `<div class="sub">${moving ? '▲ ▼ to move · OK when done' : 'OK to show or hide · Move to reorder · Back to finish'}</div>` +
      '<div class="rows" data-scroll="y">' + rows + '</div></div>';
  }

  // ---------- Google Photos screen ----------

  function readPhotoStatus() {
    try {
      photoStatus = typeof native.photosStatus === 'function' ? JSON.parse(native.photosStatus()) : null;
    } catch (e) { photoStatus = null; }
    return photoStatus || { configured: false, state: 'unlinked' };
  }

  function photosLabel() {
    const s = readPhotoStatus();
    if (!s.configured) return 'Not set up';
    switch (s.state) {
      case 'signin': return 'Sign in on Mac';
      case 'syncing': return 'Downloading…';
      case 'ready': return s.count + ' photos';
      case 'empty': return 'Folder is empty';
      case 'error': return 'Needs attention';
      default: return '';
    }
  }

  function ago(ms) {
    if (!ms) return 'not yet';
    const m = Math.round((Date.now() - ms) / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return m + ' min ago';
    const h = Math.round(m / 60);
    return h < 24 ? h + (h === 1 ? ' hour ago' : ' hours ago') : Math.round(h / 24) + ' days ago';
  }

  function qr(url) {
    if (!url || typeof window.qrcode !== 'function') return '';
    const q = window.qrcode(0, 'M');
    q.addData(url);
    q.make();
    return '<div class="qr">' + q.createSvgTag({ cellSize: 4, margin: 0 }) + '</div>';
  }

  function prow(action, label, val) {
    return `<div class="srow" tabindex="-1" data-f data-key="ph:${action}" data-action="${action}">` +
      `<span>${label}</span><span class="val">${val || ''}</span></div>`;
  }

  function photosHtml() {
    const s = photoStatus || readPhotoStatus();
    const folder = esc(s.folder || 'your Drive folder');
    let body = '';
    if (!s.configured) {
      body = '<p class="ph-p err">Photos aren\'t set up in this build.</p>';
    } else if (s.state === 'signin') {
      body = '<p class="ph-p">Show photos from a Google Drive folder, with the place each was taken.</p>' +
        '<p class="ph-p">One-time step on your Mac: in the TV project folder, run <b>./google-signin.sh</b>, sign in, ' +
        'and choose the folder. This screen updates by itself when it\'s done.</p>' +
        (s.error ? `<p class="ph-p err">${esc(s.error)}</p>` : '');
    } else if (s.state === 'syncing') {
      body = `<p class="ph-p">Downloading photos from <b>${folder}</b>… ${s.total ? `<b>${s.done}</b> of ${s.total}` : ''}</p>` +
        '<p class="ph-p">They\'ll start appearing in the background as they arrive.</p>';
    } else {
      body = (s.state === 'error' && s.error ? `<p class="ph-p err">${esc(s.error)}</p>` : '') +
        (s.state === 'empty'
          ? `<p class="ph-p">No photos in <b>${folder}</b> yet. Add some from your phone or computer, then refresh.</p>`
          : `<p class="ph-p">Showing <b>${s.count}</b>${s.found > s.count ? ` of ${s.found}` : ''} photos from <b>${folder}</b>. ` +
            `Checked ${ago(s.lastSync)}; new photos in the folder appear within a couple of hours.</p>`) +
        skippedHtml(s.skipped) +
        (window.Dashboard.hiddenCount() ? prow('ph-unhide', `Unhide hidden photos (${window.Dashboard.hiddenCount()})`, '') : '') +
        prow('ph-sync', 'Refresh now', '') +
        prow('ph-disconnect', 'Disconnect', disconnectArmed ? 'Press OK again' : '');
    }
    return '<div class="scrim"></div><div class="panel photos-panel"><h2>Photos</h2>' +
      '<div class="rows" data-scroll="y">' + body + '</div></div>';
  }

  // Quality check results, e.g. "Skipped: 18 portrait · 6 low resolution · 3 blurry".
  function skippedHtml(skipped) {
    const parts = Object.keys(skipped || {}).map((k) => skipped[k] + ' ' + k);
    if (!parts.length) return '';
    return `<p class="ph-p">Skipped by the quality check: ${esc(parts.join(' · '))}. ` +
      'Only sharp, well-exposed landscape photos (about 1080p or better) are shown.</p>';
  }

  function startPhotoPoll() {
    clearInterval(photoPoll);
    let last = JSON.stringify(photoStatus);
    photoPoll = setInterval(() => {
      if (screen !== 'photos') { clearInterval(photoPoll); return; }
      readPhotoStatus();
      const now = JSON.stringify(photoStatus);
      if (now !== last) { last = now; refreshPhotosScreen(); }
    }, 1500);
  }

  function refreshPhotosScreen() {
    if (screen !== 'photos') return;
    const keep = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.key : null;
    renderOverlay();
    restoreFocus(keep);
  }

  function notesHtml() {
    let url = '';
    try { url = typeof native.notesUrl === 'function' ? native.notesUrl() : ''; } catch (e) { url = ''; }
    const body = url
      ? '<div class="ph-wrap">' + qr(url) + '<div class="ph-text">Scan with a phone on the home Wi-Fi, or open<br>' +
        `<b>${esc(url.replace(/^https?:\/\//, ''))}</b>` +
        '<div class="ph-hint">Post notes, set up rotating chores, and link your phone so the TV knows who\'s home. ' +
        'Only devices on your home Wi-Fi can open it.</div></div></div>'
      : '<p class="ph-p err">The TV has no network address right now, so phones can\'t reach it.</p>';
    return '<div class="scrim"></div><div class="panel photos-panel"><h2>Flat app</h2>' +
      '<div class="rows" data-scroll="y">' + body + '</div></div>';
  }

  // ---------- Location screen ----------
  // Search uses Open-Meteo's free geocoder (no key); "Detect" asks ipwho.is where this
  // network's address is, then looks that town up in the geocoder for its district.

  function readPlace() {
    try { return typeof native.getPlace === 'function' ? JSON.parse(native.getPlace()) : null; } catch (e) { return null; }
  }

  function placeLabel(p, full) {
    if (!p) return '';
    const parts = [p.name];
    if (full && p.admin2 && p.admin2 !== p.name) parts.push(p.admin2);
    if (p.admin1 && p.admin1 !== p.name) parts.push(p.admin1);
    if (full && p.countryName) parts.push(p.countryName);
    else if (p.country) parts.push(p.country);
    return parts.join(', ');
  }

  function locationHtml() {
    const cur = readPlace();
    const results = loc.results.map((p, i) =>
      `<div class="srow" tabindex="-1" data-f data-key="loc:r${i}" data-action="loc-pick" data-i="${i}">` +
      `<span>${esc(p.name)}</span><span class="val">${esc(placeLabel(p, true).slice(p.name.length + 2))}</span></div>`).join('');
    const status = loc.busy ? `<p class="ph-p">${esc(loc.busy)}</p>`
      : loc.error ? `<p class="ph-p err">${esc(loc.error)}</p>`
      : loc.searched && !loc.results.length ? `<p class="ph-p">No places called “${esc(loc.searched)}”. Try a nearby town or city.</p>` : '';
    return '<div class="scrim"></div><div class="panel photos-panel"><h2>Location</h2><div class="rows" data-scroll="y">' +
      `<p class="ph-p">${cur ? `Home is set to <b>${esc(placeLabel(cur))}</b>.` : 'Home isn\'t set yet.'} ` +
      'It\'s used for weather, air quality, the traffic map and, in India, official weather alerts.</p>' +
      '<div class="srow loc-search" tabindex="-1" data-f data-key="loc:q">' +
      `<input id="locQuery" type="search" enterkeyhint="search" autocomplete="off" spellcheck="false" maxlength="60" ` +
      `placeholder="Search for your city or town" value="${esc(loc.query)}"></div>` +
      status + results +
      (loc.results.length ? '<div class="divider"></div>' : '') +
      prow('loc-detect', 'Detect from internet connection', '') +
      (cur ? prow('loc-clear', 'Clear location', '') : '') +
      '<div class="info">OK on the search box opens the keyboard · OK again to search<br>' +
      'Searches go to open-meteo.com; Detect asks ipwho.is</div>' +
      '</div></div>';
  }

  function refreshLocation(focusKey) {
    if (screen !== 'location') return;
    renderOverlay();
    restoreFocus(focusKey || (document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.key : null));
  }

  // The TV's on-screen keyboard doesn't always open for a field focused from script.
  function keyboard(show) {
    try { if (typeof native.keyboard === 'function') native.keyboard(show); } catch (e) { /* older bridge */ }
  }

  const geoLang = () => ((navigator.language || 'en').slice(0, 2));

  async function geocode(name, country) {
    const r = await fetch('https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language=' + geoLang() +
      '&name=' + encodeURIComponent(name) + (country ? '&countryCode=' + country : ''));
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return ((await r.json()).results || []).map((g) => ({
      name: g.name, lat: g.latitude, lon: g.longitude, country: g.country_code || '', countryName: g.country || '',
      admin1: g.admin1 || '', admin2: g.admin2 || ''
    }));
  }

  async function searchPlaces(q) {
    q = q.trim();
    loc.query = q;
    if (q.length < 2) return;
    keyboard(false);
    loc = Object.assign(loc, { searched: q, results: [], busy: 'Searching…', error: '' });
    refreshLocation('loc:q');
    try { loc.results = await geocode(q); } catch (e) { loc.error = 'Couldn\'t search: check the TV\'s internet connection.'; }
    loc.busy = '';
    refreshLocation(loc.results.length ? 'loc:r0' : 'loc:q');
  }

  async function detectPlace() {
    loc = Object.assign(loc, { results: [], searched: '', busy: 'Finding this network\'s location…', error: '' });
    refreshLocation('ph:loc-detect');
    try {
      const ip = await (await fetch('https://ipwho.is/')).json();
      if (!ip.success) throw new Error(ip.message || 'lookup failed');
      let p = { name: ip.city, lat: ip.latitude, lon: ip.longitude, country: ip.country_code || '', admin1: ip.region || '', admin2: '' };
      // The geocoder knows the district; take its nearest match for the same town.
      try {
        const near = (await geocode(ip.city, ip.country_code))
          .map((g) => Object.assign(g, { d: Math.hypot(g.lat - p.lat, (g.lon - p.lon) * Math.cos(p.lat * Math.PI / 180)) }))
          .sort((a, b) => a.d - b.d)[0];
        if (near && near.d < 0.5) p = near; // within ~50 km
      } catch (e) { /* keep the IP lookup's answer */ }
      loc.busy = '';
      pickPlace(p, 'Detected: ');
    } catch (e) {
      loc.busy = '';
      loc.error = 'Couldn\'t detect the location. Search for your town instead.';
      refreshLocation('loc:q');
    }
  }

  function pickPlace(p, prefix) {
    let ok = false;
    try { ok = typeof native.setPlace === 'function' && native.setPlace(JSON.stringify(p)); } catch (e) { ok = false; }
    if (!ok) { toast('Couldn\'t save that location'); return; }
    loc = { query: '', searched: '', results: [], busy: '', error: '' };
    toast((prefix || 'Home set to ') + placeLabel(p));
    if (window.Dashboard.placeChanged) window.Dashboard.placeChanged();
    open('settings');
  }

  function allHtml() {
    const list = apps.slice().sort(byLabel);
    return '<div class="allapps"><h2>All apps</h2><div class="grid" data-scroll="y">' +
      list.map((a) => tile(a, 'all:' + a.pkg)).join('') + '</div></div>';
  }

  function applyTheme() {
    const bg = BACKGROUNDS.find((b) => b[0] === state.bg) || BACKGROUNDS[0];
    $('bg').style.background = bg[2];
    const f = FONTS.find((x) => x[0] === state.font) || FONTS[0];
    stage.style.setProperty('--font', f[2]);
    stage.style.setProperty('--weight', f[3]);
    stage.style.setProperty('--clock-weight', f[4]);
    tick();
  }

  function render() {
    const keep = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.key : null;
    applyTheme();
    renderHome();
    renderOverlay();
    restoreFocus(keep || lastFocus[screen]);
  }

  // ---------- focus & navigation ----------

  function scope() { return screen === 'home' ? $('home') : $('overlay'); }

  function focusables() {
    return Array.prototype.filter.call(scope().querySelectorAll('[data-f]'), (el) => el.offsetParent !== null);
  }

  function focus(el) {
    if (!el) return;
    el.focus({ preventScroll: true });
    ensureVisible(el);
    lastFocus[screen] = el.dataset.key;
    onFocusChanged(el);
  }

  function restoreFocus(key) {
    const s = scope();
    let el = key ? s.querySelector('[data-key="' + key.replace(/"/g, '\\"') + '"]') : null;
    if (!el) el = focusables()[0];
    focus(el);
  }

  // Manual scrolling: scrollIntoView would also scroll the overflow:hidden stage.
  function ensureVisible(el) {
    const c = el.closest('[data-scroll]');
    if (!c) return;
    if (c.dataset.scroll === 'shelf') {
      // First row sits under the wash; lower rows slide the whole shelf up.
      const inner = c.firstElementChild;
      const first = inner.firstElementChild;
      const y = !first || el.offsetTop <= first.offsetTop ? 0 : el.offsetTop - 380;
      inner.style.transform = 'translateY(' + -y + 'px)';
      c.classList.toggle('scrolled', y > 0);
      return;
    }
    const pad = 40;
    let top = 0, left = 0, n = el;
    while (n && n !== c) { top += n.offsetTop; left += n.offsetLeft; n = n.offsetParent; }
    if (c.dataset.scroll === 'y') {
      const bottom = top + el.offsetHeight;
      if (top - pad < c.scrollTop) c.scrollTop = Math.max(0, top - pad);
      else if (bottom + 110 > c.scrollTop + c.clientHeight) c.scrollTop = bottom + 110 - c.clientHeight; // clear the fade
    } else {
      const right = left + el.offsetWidth;
      const edge = 120;
      if (left - edge < c.scrollLeft) c.scrollLeft = Math.max(0, left - edge);
      else if (right + edge > c.scrollLeft + c.clientWidth) c.scrollLeft = right + edge - c.clientWidth;
    }
  }

  // Spatial navigation: pick the nearest focusable in the pressed direction,
  // strongly preferring ones in the same row/column.
  function nav(dir) {
    const els = focusables();
    const cur = document.activeElement;
    if (!cur || els.indexOf(cur) < 0) { focus(els[0]); return; }
    const r = cur.getBoundingClientRect();
    let best = null, bestScore = Infinity;
    for (const el of els) {
      if (el === cur) continue;
      const q = el.getBoundingClientRect();
      let main, cross;
      if (dir === 'ArrowRight' || dir === 'ArrowLeft') {
        const ahead = dir === 'ArrowRight' ? q.left + q.width / 2 > r.left + r.width / 2 + 1 : q.left + q.width / 2 < r.left + r.width / 2 - 1;
        if (!ahead) continue;
        main = dir === 'ArrowRight' ? Math.max(0, q.left - r.right) : Math.max(0, r.left - q.right);
        cross = Math.max(0, Math.max(q.top, r.top) - Math.min(q.bottom, r.bottom));
      } else {
        const ahead = dir === 'ArrowDown' ? q.top + q.height / 2 > r.top + r.height / 2 + 1 : q.top + q.height / 2 < r.top + r.height / 2 - 1;
        if (!ahead) continue;
        main = dir === 'ArrowDown' ? Math.max(0, q.top - r.bottom) : Math.max(0, r.top - q.bottom);
        cross = Math.max(0, Math.max(q.left, r.left) - Math.min(q.right, r.right));
        if (cross === 0) cross = Math.abs(q.left - r.left) * 0.05; // aligned columns win ties
      }
      const score = main + cross * 4;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) focus(best);
  }

  function onFocusChanged(el) {
    if (screen !== 'home') return;
    const pkg = el.dataset.pkg;
    const a = pkg && byPkg[pkg];
    if (state.layout === 'dashboard') {
      window.Dashboard.onFocus(a);
    } else if (state.layout === 'shelf') {
      setWash(a);
    } else if (state.layout === 'list') {
      const pv = document.querySelector('#home .preview');
      if (!pv) return;
      if (a) { pv.innerHTML = tile(a, '', { focusable: false }); pv.classList.add('on'); }
      else pv.classList.remove('on');
    } else if (state.layout === 'dock') {
      const n = document.querySelector('#home .dock-name');
      if (!n) return;
      if (a) { n.textContent = a.label; n.classList.add('on'); }
      else n.classList.remove('on');
    }
  }

  // Crossfades between two layers. The wash image is tiny (32x18) and the
  // browser's upscaling blurs it for free — no CSS blur filter on the TV GPU.
  let washKey = null, washFront = 0;
  function setWash(a) {
    const layers = document.querySelectorAll('#home .wash .w');
    if (layers.length < 2) return;
    const key = a ? a.pkg : '';
    if (key === washKey) return;
    washKey = key;
    washFront = 1 - washFront;
    const next = layers[washFront];
    next.style.backgroundImage = a ? 'url("' + img(a, 'wash') + '")' : 'none';
    next.style.backgroundColor = a ? 'hsl(' + hue(a.pkg) + ',25%,22%)' : '#2a2a2e';
    next.classList.add('on');
    layers[1 - washFront].classList.remove('on');
  }

  // ---------- actions ----------

  function open(name) {
    lastFocus[screen] = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.key : lastFocus[screen];
    screen = name;
    if (name === 'photos') { readPhotoStatus(); disconnectArmed = false; startPhotoPoll(); }
    if (name === 'location') { loc = { query: '', searched: '', results: [], busy: '', error: '' }; lastFocus.location = null; } // start on the search box
    renderOverlay();
    restoreFocus(lastFocus[name]);
  }

  function back() {
    if (moving) { moving = null; renderOverlay(); restoreFocus(lastFocus.edit); return; }
    resetArmed = false;
    if (screen === 'edit' || screen === 'photos' || screen === 'notes' || screen === 'location') { open('settings'); return; }
    if (screen === 'settings' || screen === 'all') {
      screen = 'home';
      renderOverlay();
      restoreFocus(lastFocus.home);
      return;
    }
    restoreFocus(null); // Back on the home screen: jump to the first app
  }

  function goHome() {
    moving = null;
    resetArmed = false;
    screen = 'home';
    renderOverlay();
    restoreFocus(null);
  }

  function cycle(key, step) {
    const def = SETTINGS.find((d) => d.key === key);
    const i = def.options.findIndex((o) => o[0] === state[key]);
    const next = def.options[(i + step + def.options.length) % def.options.length];
    state[key] = next[0];
    save();
    render();
    if (window.Dashboard.settingsChanged) window.Dashboard.settingsChanged();
  }

  function toggleHidden(pkg) {
    const i = state.hidden.indexOf(pkg);
    if (i >= 0) state.hidden.splice(i, 1); else state.hidden.push(pkg);
    save();
    render();
  }

  function moveApp(step) {
    const order = installedOrder();
    const i = order.indexOf(moving);
    const j = i + step;
    if (i < 0 || j < 0 || j >= order.length) return;
    order[i] = order[j];
    order[j] = moving;
    const missing = state.order.filter((p) => !byPkg[p]);
    state.order = order.concat(missing);
    save();
    renderHome();
    renderOverlay();
    restoreFocus('mv:' + moving);
  }

  function activate(el) {
    if (!el || !el.dataset) return;
    if (moving) { moving = null; renderOverlay(); restoreFocus(el.dataset.key); return; }
    const d = el.dataset;
    if (d.setting) { cycle(d.setting, 1); return; }
    if (d.action !== 'reset') resetArmed = false;
    if (d.action !== 'ph-disconnect') disconnectArmed = false;
    switch (d.action) {
      case 'all': open('all'); break;
      case 'settings': open('settings'); break;
      case 'edit': open('edit'); break;
      case 'photos': open('photos'); break;
      case 'notes': open('notes'); break;
      case 'location': open('location'); break;
      case 'loc-pick': pickPlace(loc.results[+d.i]); break;
      case 'loc-detect': detectPlace(); break;
      case 'loc-clear':
        if (typeof native.clearPlace === 'function') native.clearPlace();
        toast('Location cleared');
        if (window.Dashboard.placeChanged) window.Dashboard.placeChanged();
        refreshLocation('loc:q');
        break;
      case 'ph-unhide': toast(window.Dashboard.unhideAll() + ' photos back in the slideshow'); refreshPhotosScreen(); break;
      case 'ph-sync': native.photosSync(); toast('Checking the Drive folder…'); break;
      case 'ph-disconnect':
        if (!disconnectArmed) { disconnectArmed = true; refreshPhotosScreen(); break; }
        disconnectArmed = false;
        native.photosDisconnect();
        toast('Google Photos disconnected');
        setTimeout(refreshPhotosScreen, 500);
        break;
      case 'tvsettings': native.openTvSettings(); break;
      case 'toggle': toggleHidden(el.parentNode.dataset.pkg); break;
      case 'move':
        moving = el.parentNode.dataset.pkg;
        renderOverlay();
        restoreFocus('mv:' + moving);
        break;
      case 'reset':
        if (!resetArmed) { resetArmed = true; renderOverlay(); restoreFocus('act:reset'); break; }
        resetArmed = false;
        state = JSON.parse(JSON.stringify(DEFAULTS));
        save();
        refreshApps();
        if (window.Dashboard.settingsChanged) window.Dashboard.settingsChanged();
        break;
      default:
        if (d.pkg) launch(d.pkg);
    }
  }

  // ---------- input ----------

  const DIRS = { ArrowUp: 1, ArrowDown: 1, ArrowLeft: 1, ArrowRight: 1 };

  document.addEventListener('keydown', (e) => {
    const k = e.key;
    // Typing in the location search box: letters, Backspace and ◀ ▶ edit the text.
    if (e.target && e.target.id === 'locQuery') {
      const box = e.target;
      if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); box.blur(); focus(box.parentNode); nav(k); }
      else if (k === 'Escape') { e.preventDefault(); back(); }
      else if (k === 'Enter') {
        loc.query = box.value;
        // OK with a new query searches; otherwise it falls through and opens the keyboard.
        if (box.value.trim().length >= 2 && box.value.trim() !== loc.searched) { e.preventDefault(); searchPlaces(box.value); }
        else keyboard(true);
      }
      return;
    }
    // Photo-frame view: ◀ ▶ photos, OK pause, ▼ hide. Any other key wakes the home screen.
    if (state.layout === 'dashboard' && screen === 'home' && window.Dashboard.isIdle() && window.Dashboard.idleKey(k)) {
      e.preventDefault();
      return;
    }
    // On the dashboard, the first press after it fades to a photo frame only wakes it.
    if (window.Dashboard.wake() && state.layout === 'dashboard' && screen === 'home') { e.preventDefault(); return; }
    if (DIRS[k]) {
      e.preventDefault();
      if (moving) {
        if (k === 'ArrowUp' || k === 'ArrowDown') moveApp(k === 'ArrowUp' ? -1 : 1);
        return;
      }
      const el = document.activeElement;
      if (screen === 'settings' && el && el.dataset.setting && (k === 'ArrowLeft' || k === 'ArrowRight')) {
        cycle(el.dataset.setting, k === 'ArrowLeft' ? -1 : 1);
        return;
      }
      nav(k);
    } else if (k === 'Enter' || k === ' ') {
      e.preventDefault();
      const el = document.activeElement;
      if (el && el.dataset && el.dataset.key === 'loc:q') { $('locQuery').focus(); keyboard(true); return; } // into the text box
      if (!e.repeat) activate(el);
    } else if (k === 'Escape' || k === 'Backspace' || k === 'BrowserBack' || k === 'GoBack') {
      e.preventDefault();
      back();
    } else if (k === 'ContextMenu' || (native.mock && (k === 'm' || k === 'M'))) {
      e.preventDefault();
      if (screen === 'settings') back(); else open('settings');
    }
  });

  // Mouse support for the browser preview.
  document.addEventListener('click', (e) => {
    if (e.target.id === 'locQuery') return; // let the text box take the click
    const el = e.target.closest('[data-f]');
    if (el) { focus(el); activate(el); }
  });

  // ---------- clock, scaling, toast ----------

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function tick() {
    const d = new Date();
    const h = d.getHours();
    $('clock').textContent = state.clock24 ? pad(h) + ':' + pad(d.getMinutes()) : (h % 12 || 12) + ':' + pad(d.getMinutes());
    $('date').textContent = d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
  }

  function fit() {
    const s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    const x = (window.innerWidth - 1920 * s) / 2;
    const y = (window.innerHeight - 1080 * s) / 2;
    stage.style.transform = `translate(${x}px, ${y}px) scale(${s})`;
  }

  let toastTimer = 0;
  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('on'), 2400);
  }

  // ---------- hooks called from MainActivity ----------

  window.ClearHome = {
    onBack: () => {
      if (state.layout === 'dashboard' && screen === 'home' && window.Dashboard.isIdle()) {
        if (!window.Dashboard.idleKey('Escape')) window.Dashboard.wake();
        return;
      }
      back();
    },
    onHome: goHome,
    onMenu: () => { if (screen === 'settings') back(); else open('settings'); },
    onResume: () => { tick(); window.Dashboard.wake(); refreshApps(); window.Dashboard.refresh(); },
    clock24: () => state.clock24,
    pref: (k) => state[k],
    placeChanged: () => {
      if (window.Dashboard.placeChanged) window.Dashboard.placeChanged();
      if (screen === 'settings') { renderOverlay(); restoreFocus(lastFocus.settings); }
    },
    hourly: () => state.hourly,
    acChanged: () => window.Dashboard.refresh(),
    mediaChanged: () => window.Dashboard.media(),
    openScreen: (name) => open(name),
    photosChanged: () => { readPhotoStatus(); refreshPhotosScreen(); window.Dashboard.refresh(); },
    appsChanged: refreshApps,
    toast: toast
  };

  window.addEventListener('resize', fit);
  fit();
  setInterval(tick, 10000);
  refreshApps();
  window.Dashboard.start();
})();
