# Clear Home

A calm, photo-first home screen for Android TV / Google TV. It replaces the busy stock
launcher with your own photos, the time and weather, a few glanceable cards and a
Dock-style app row.

Clear Home was started by [Ajzal](https://github.com/Mohammedajzal).

![Dashboard](design/dashboard/1-dashboard.png)

> [!WARNING]
> **Use at your own risk.** Clear Home is an unofficial hobby project, provided "as is",
> with no warranty of any kind. It is not affiliated with Google, TCL or any TV maker.
> It installs a side-loaded app, needs developer options on your TV, and (if you choose)
> disables your TV's stock launcher. Those changes can be undone (see
> [Undo everything](#undo-everything)), but things can still go wrong on your particular TV.
> **The authors and anyone sharing this repository are not responsible or liable** for any
> damage, data loss, bricked or misbehaving devices, voided warranties, security issues or
> any other problem that comes from using it. Read the scripts before you run them, and
> only install it on a TV you own or are allowed to change. See also the [MIT license](LICENSE).

## What it does

- **Clock and weather** for the location you choose on the TV (Open-Meteo, no account or
  key): animated icon, feels-like, high/low, air quality, rain timing, optional hourly forecast.
- **Photo background**: built-in sample photos, or your own from a Google Drive folder
  (optional setup). Photos are quality-checked (landscape, sharp, well exposed) before showing.
- **Idle photo frame**: after a few seconds without input, cards and the app row fade away.
- **Dock-style app row** with magnification, real app banners, and Settings at the end.
- **Settings on the TV**: layout (Dashboard / Shelf / List / Dock / Grid), background, font,
  12/24-hour clock, °C/°F, photo timing, which cards show, and choosing/ordering apps.
- **Flat app (optional)**: a small web app on your home Wi-Fi (scan the QR on the TV) for
  sticky notes, chores, timers, guest Wi-Fi and "who's home".
- **Optional cards** that need your own accounts or keys: air conditioners on Tapo P110
  smart plugs, a live traffic map (Mapbox) or traffic times (TomTom), and official weather
  alerts (India only, from NDMA SACHET).
- **TCL remotes**: the TCL button can open Clear Home (accessibility service, TCL TVs only).

## Requirements

- An **Android TV or Google TV** running Android 7 or newer, on the same network as your
  computer. Developed on a TCL Google TV; also tried on a Philips Android TV (Android 11).
  Other TVs should work but aren't tested.
- A **Mac** (the helper scripts assume macOS paths; Linux works if you set `ADB` and
  `ANDROID_HOME`).
- **Android Studio** installed once (for its bundled Java, the Android SDK and `adb`).
  Gradle isn't used. In Android Studio's SDK Manager, make sure an Android SDK Platform
  and Build-Tools are installed.
- **Python 3** for the optional setup scripts.

## Quick start

### 1. Turn on developer options and network debugging on the TV

1. Settings → System → About → select **Android TV OS build** 7 times
   ("You are now a developer").
2. Settings → System → Developer options → turn on **USB debugging** (on some TVs also
   **Network debugging** / **ADB over network**).
3. Find the TV's IP address: Settings → Network & Internet → your network.

### 2. Build and install

```bash
git clone https://github.com/twilighty-abhi/Clear-Home.git
cd Clear-Home
./build.sh                    # → build/ClearHome.apk
./install.sh 192.168.1.50     # your TV's IP; remembered in .tv afterwards
```

**Or skip building:** download the APK from
[Releases](https://github.com/twilighty-abhi/Clear-Home/releases/latest) and run
`adb connect <TV-IP>:5555` then `adb install ClearHome-*.apk`. (To switch to your own build
later, uninstall that one first: the signing keys differ.)

The first time, the TV asks **"Allow USB debugging?"**. Tick *Always allow from this
computer* and accept. `install.sh` installs the app and opens it.

The app is signed with a key generated on your computer (`keystore/`, git-ignored). Keep
that folder: updates must be signed with the same key, or you'll need to uninstall first.

### 3. Set it up on the TV

Open **Settings**: the gear at the end of the app row, or the remote's **Menu** button.

- **Location**: choose *Location*, then either
  - press OK on the search box, type your town with the on-screen keyboard and pick it
    from the list, or
  - choose *Detect from internet connection* (approximate; uses your public IP).

  Until a location is set, the weather area shows "Choose your location".
- **Temperature**: *Auto* uses °F for the US and a few other countries, °C elsewhere.
- **Choose & order apps** hides apps you never use and puts the rest in order.

Remote controls: arrows move, OK selects, Back goes back. ◀ ▶ change a setting's value.
In the photo frame: ◀ ▶ change photos, OK pauses, ▼ hides a photo.

### 4. Optional: make it the Home button

Clear Home works as a normal app without this step. To have the remote's Home button open it:

```bash
./home.sh status    # which app the Home button opens now
./home.sh use       # make Clear Home the Home app (saves the original to .home-backup)
./home.sh restore   # put the stock launcher back
```

Google TV usually ignores the normal "default home app" setting, so `use` falls back to
**disabling the stock launcher for the current user** (`pm disable-user`). Nothing is
uninstalled and no root is needed, but while it's disabled you lose the stock home screen,
its recommendations and possibly some TV-maker features. Keep `.home-backup`;
`./home.sh restore` uses it to undo the change.

## Optional extras

Each service reads a file in `secrets/` (git-ignored). Copy the matching `*.example.json`,
fill it in, and run the script: it sends the file to the TV over adb, where it's moved
into the app's private storage and the copy is deleted. **Never commit `secrets/`.**

| Card | What you need | Then run |
| --- | --- | --- |
| Photos from Google Drive | A Google Cloud project with the Drive API and a *Desktop app* OAuth client → `secrets/google-desktop.json` | `./google-signin.sh` (sign in in your browser, then pick the folder) |
| Photo dates/places from Google Photos | A Google Photos Takeout export | `./takeout-sidecars.sh ~/Downloads/takeout-*.zip` |
| Air conditioners | Tapo P110 plugs; in the Tapo app: Me → Third-Party Services → *Third-Party Compatibility* on → `secrets/tapo.json` | `./tapo-signin.sh` |
| Traffic map / traffic times | A Mapbox public token (map) or TomTom key (times) → `secrets/commute.json`. Leave out `area` to use the location chosen on the TV. | `./commute-setup.sh` |

Weather alerts need no setup, but only work for locations in India.

**TCL button** (TCL TVs only): allow Clear Home under TCL's **Auto-start** (Guard app), then
Settings → System → Accessibility → **Clear Home remote button**.

**Flat app on a Raspberry Pi** (advanced): `pi/install.sh` moves the flat app's server to a
Pi (`PI=user@host pi/install.sh`). `docs/cloudflare-setup.html` describes putting it behind
Cloudflare Access to reach it from outside your home. Read both before using them.

## Privacy and network use

- Your location is stored only on the TV. It's sent to **open-meteo.com** for weather and
  air quality, and to **ipwho.is** only if you choose *Detect*. Alerts are fetched from
  **sachet.ndma.gov.in** (India only).
- Optional cards talk to Google (Drive), Mapbox/TomTom, and your Tapo plugs, only after
  you set them up.
- The **flat app web server runs on port 8642 on your home network**. Anyone on the same
  Wi-Fi can open it and leave a note; set a **flat PIN** in the web app to control who can
  link a phone and manage the rest. Don't install it on networks you don't trust, and
  don't expose the port to the internet without proper protection.

## Try it in a browser first

You can preview the interface without a TV:

```bash
python3 -m http.server 8765
```

Open http://localhost:8765/app/assets/index.html. Arrows move, Enter is OK, Esc is Back,
M opens Settings. `app/assets/mock.js` supplies sample apps and data (names and readings
are made up).

## Undo everything

```bash
./home.sh restore                                   # only if you ran ./home.sh use
adb uninstall dev.local.clearhome                   # removes the app and all its data
```

(`adb` lives at `~/Library/Android/sdk/platform-tools/adb` if it isn't on your PATH.)
Then turn off developer options on the TV if you don't need them: Settings → System →
Developer options → off.

## Troubleshooting

- **`install.sh` can't connect**: check the IP, that the TV and computer are on the same
  network, and that USB/network debugging is on. Accept the prompt on the TV.
- **Weather is missing**: set a location in Settings → Location, and check the TV has internet.
- **Search keyboard doesn't appear**: press OK on the search box again.
- **Logs**: `adb logcat -s ClearHome` shows the app's log, including the page's console.

## Project layout

| Path | What |
| --- | --- |
| `app/assets/` | The home screen: `index.html`, `app.js` (navigation, settings, screens), `dashboard.js` (photo dashboard), `app.css`, `mock.js` (browser preview data), `web/` (flat web app) |
| `app/src/dev/local/clearhome/` | Android side: `MainActivity`/`WebPage` (WebView + bridge), `Core`, `Place` (location), `GoogleDrive`, `Tapo`, `Commute`, `Alerts`, `House` (flat app server), `RemoteButton` |
| `tools/` | Mac-side helpers behind the `*.sh` scripts |
| `pi/` | Optional Raspberry Pi flat server |
| `design/` | Screenshots and preview assets |

The UI is plain HTML/CSS/JS hosted in a WebView by a small Android app, built with the
Android SDK command-line tools only.

## Credits

Started by [Ajzal](https://github.com/Mohammedajzal). Sample background photos are from
[Unsplash](https://unsplash.com) (via picsum.photos); fonts are
[Outfit](https://github.com/Outfitio/Outfit-Fonts) and Caveat (OFL); QR codes use
[qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) (MIT). Weather by
[Open-Meteo](https://open-meteo.com).

## License

[MIT](LICENSE). You're free to use, change and share it; it comes with no warranty.
