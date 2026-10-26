/* Browser preview only: stands in for the Android bridge with sample apps
   (roughly the apps installed on the TV, drawn as plain colour cards).
   On the TV, MainActivity injects the real window.Android before this runs. */
(function () {
  if (window.Android) return;

  // Official artwork copied from the TV's own apps (design/art/art.js, preview only).
  document.write('<script src="/design/art/art.js"><\/script>');

  // [package, label, background, text colour, is TV app, has banner]
  const APPS = [
    ['com.netflix.ninja', 'Netflix', '#ffffff', '#e50914', true, true],
    ['com.google.android.youtube.tv', 'YouTube', '#ffffff', '#ff0033', true, true],
    ['com.amazon.amazonvideo.livingroom', 'Prime Video', '#1a98ff', '#ffffff', true, true],
    ['com.apple.atve.androidtv.appletv', 'Apple TV', '#111111', '#ffffff', true, true],
    ['in.startv.hotstar', 'JioHotstar', '#0c1445', '#ffffff', true, true],
    ['com.spotify.tv.android', 'Spotify', '#121212', '#1ed760', true, true],
    ['com.google.android.youtube.tvmusic', 'YouTube Music', '#0f0f0f', '#ff0033', true, true],
    ['com.sonyliv', 'Sony LIV', '#000000', '#f5c518', true, true],
    ['com.stremio.one', 'Stremio', '#1b1446', '#8a5cf6', true, true],
    ['com.graymatrix.did', 'ZEE5', '#2b0a3d', '#ffffff', true, true],
    ['com.mxtech.videoplayer.television', 'MX Player', '#0a63e8', '#ffffff', true, true],
    ['com.neilturner.aerialviews', 'Aerial Views', '#3b6e8f', '#ffffff', true, false],
    ['com.tcl.tv', 'TV', '#1f2a36', '#ffffff', true, false],
    ['com.tcl.gallery', 'Gallery', '#24324a', '#ffffff', true, true],
    ['com.android.chrome', 'Chrome', '#1d2b3d', '#ffffff', false, false]
  ];

  const uri = (s) => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(s);
  const svg = (w, h, colour, label, size, fg) => uri(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">` +
    `<rect width="${w}" height="${h}" fill="${colour}"/>` +
    `<text x="50%" y="53%" fill="${fg || '#f4f1ec'}" font-family="Helvetica, Arial, sans-serif" font-size="${size}" ` +
    `font-weight="700" text-anchor="middle" dominant-baseline="middle">${label}</text></svg>`);

  const banner = (a) => svg(480, 270, a[2], a[1], a[1].length > 10 ? 52 : 64, a[3]);

  // Soft two-colour field standing in for the TV's tiny upscaled banner.
  const wash = (bg, fg) => uri(
    '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="18" viewBox="0 0 32 18">' +
    `<defs><radialGradient id="g" cx="30%" cy="40%" r="80%"><stop offset="0" stop-color="${fg}"/>` +
    `<stop offset="1" stop-color="${bg}"/></radialGradient></defs><rect width="32" height="18" fill="url(#g)"/></svg>`);

  let mockPhotos = { configured: true, state: 'signin' };
  window.mockPhotosState = (st) => { mockPhotos = Object.assign({ configured: true }, st); };

  const find = (pkg) => APPS.find((a) => a[0] === pkg);
  const toast = (m) => window.ClearHome && window.ClearHome.toast(m);

  window.Android = {
    mock: true,
    getApps: () => JSON.stringify(APPS.map((a) => {
      const art = (window.MOCK_ART || {})[a[0]];
      return { pkg: a[0], label: a[1], tv: a[4], banner: art ? !!art.banner : a[5], v: 1 };
    })),
    image: (pkg, kind) => {
      const a = find(pkg);
      const art = (window.MOCK_ART || {})[pkg] || {};
      if (kind === 'banner' && art.banner) return '/design/art/' + art.banner;
      if (kind === 'icon' && (art.icon || art.banner)) return '/design/art/' + (art.icon || art.banner);
      if (kind === 'wash') return wash(a[2], a[3]);
      return kind === 'banner' ? banner(a) : svg(192, 192, a[2], a[1].charAt(0), 110, a[3]);
    },
    launch: (pkg) => toast('On the TV this opens ' + find(pkg)[1]),
    openTvSettings: () => toast('On the TV this opens Android settings'),
    openAppInfo: () => {},
    // Dashboard stand-ins. Photos are free Unsplash samples (via picsum.photos);
    // commute and AC readings are made-up sample values.
    getPhotos: () => {
      // Sample 2 is dated today, two years ago, to preview "On this day".
      const d = new Date(); d.setFullYear(d.getFullYear() - 2);
      return JSON.stringify([1, 2, 3, 4, 5, 6].map((n) => ({
        url: '/design/photos/' + n + '.jpg', title: ['Valley', 'Waterfall', 'Hills', 'Yosemite', 'Fjord', 'River'][n - 1],
        date: n === 2 ? d.toISOString() : '2024-0' + n + '-1' + n + 'T10:00:00', device: 'Galaxy S23', lum: 60 + n * 15,
        place: 'Munnar, Keralam', placeSource: 'guess'
      })));
    },
    getAlerts: () => JSON.stringify([{ type: 'Very Heavy Rain', severity: 'ALERT', until: Date.now() + 5 * 3600000,
      message: 'Very heavy rainfall likely at isolated places in the district in the next 24 hours.',
      lang: 'en', source: 'IMD', sample: true }]),
    getNotes: () => JSON.stringify([
      { id: 'n3', text: 'Dinner at 8', from: 'Sam', at: Date.now() - 5 * 60000, until: Date.now() + 3 * 3600000 },
      { id: 'n2', text: 'Tomorrow is Friday', from: 'Priya', at: Date.now() - 9 * 60000, until: Date.now() + 3 * 3600000 },
      { id: 'n1', text: 'Welcome home', from: 'Alex', at: Date.now() - 14 * 60000, until: Date.now() + 3 * 3600000 }
    ]),
    notesUrl: () => 'http://192.168.1.50:8642',
    getHouse: () => JSON.stringify({
      notes: JSON.parse(window.Android.getNotes()),
      flatmates: [{ id: 'a', name: 'Alex', colour: '#f2b705', emoji: '🦊', initials: 'Al', home: true },
        { id: 'b', name: 'Sam', colour: '#4cc3a5', emoji: '', initials: 'Sa', home: false },
        { id: 'c', name: 'Priya', colour: '#e86f6f', emoji: '🐯', initials: 'Pr', home: true }],
      chores: [{ id: 'x', name: 'Trash', every: 'day', assignee: 'Sam', done: false }, { id: 'y', name: 'Dishes', every: 'day', assignee: 'Priya', done: true }],
      tv: { allowed: true, minutes: 130, top: [{ name: 'Netflix', minutes: 80 }] },
      timers: [{ id: 't1', label: 'Pasta', duration: 600000, ends: Date.now() + 272000, paused: false, important: true },
        { id: 't2', label: 'Laundry', duration: 2400000, left: 1500000, paused: true, important: false }],
      guest: { ssid: 'Home Guest', pass: 'welcome-2026', security: 'WPA', on: true, until: Date.now() + 3 * 3600e3 }
    }),
    getNowPlaying: () => JSON.stringify({ playing: true, title: 'Nenjukkul Peidhidum', artist: 'Harris Jayaraj, Hariharan', album: 'Vaaranam Aayiram',
      app: 'Spotify', pkg: 'com.spotify.tv.android', art: '', position: 72000, duration: 291000, speed: 1, at: Date.now() }),
    getCommute: () => JSON.stringify({ mode: 'map', area: 'Home', url: '/design/traffic/map-sample.svg', updated: Date.now() - 120000, sample: true }),
    getAc: () => JSON.stringify([
      { name: 'Bedroom AC', ok: true, on: true, watts: 1120, kwhToday: 6.2, kwhMonth: 148, runMinutes: 220, sample: true },
      { name: 'Living Room AC', ok: true, on: true, watts: 1, kwhToday: 2.4, kwhMonth: 96, runMinutes: 95, sample: true }
    ]),
    // Simulated Google Photos sign-in so the screen can be previewed.
    photosStatus: () => JSON.stringify(mockPhotos),
    photosSync: () => {},
    photosDisconnect: () => { mockPhotos = { configured: true, state: 'signin' }; },
    // Location, kept in the browser for the preview (starts at a sample city).
    getPlace: () => localStorage.getItem('mock.place') ||
      JSON.stringify({ name: 'Kochi', lat: 9.9312, lon: 76.2673, country: 'IN', admin1: 'Kerala', admin2: 'Ernakulam' }),
    setPlace: (json) => { localStorage.setItem('mock.place', json); return true; },
    clearPlace: () => { localStorage.setItem('mock.place', 'null'); },
    keyboard: () => {},
    isDefaultHome: () => false,
    version: () => 'preview'
  };
})();
