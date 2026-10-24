# Terminal home for Android TV

A terminal-style home screen for Android TV and Google TV. I built it for a Grundig 65 GJQ 9200
(Google TV, Android 14). It replaces the stock home screen, which shows ads, with one dark screen:

- **Header**: `~ / home` with a block cursor, network status, date and clock.
- **Trending card**: trending titles from TMDB for the focused dock app. YouTube shows trailers,
  Nuvio shows TMDB trending, Apple TV shows Apple TV+ series, and the other apps show a mixed feed.
  It changes slide every 8 seconds. OK opens the title.
- **Dock**: eight tiles for YouTube, Apple TV, Nuvio, Kick, YT Music, Prime Video, Spotify and All apps.
  Hold OK on a tile to open its App info.
- **All apps** (`ls /apps`): every installed app in a grid, and a SYSTEM list with Inputs, Settings,
  Network, Sleep timer and Refresh trending. Hold OK on an app to pin it to the dock.

The look is brutalist: Geist Mono and Martian Mono, square corners, no shadows or gradients, and
short stepped animations only.

## Build

You need JDK 17 and the Android SDK command-line tools.

1. Get a TMDB API key (v3 key or v4 read token) at https://www.themoviedb.org/settings/api.
2. Make `keys.properties` in the project root. Git ignores this file; do not commit it.

   ```properties
   TMDB_API_KEY=your-key-here
   ```

   Without a key the launcher works, but it has no trending card.
3. Build:

   ```sh
   export ANDROID_HOME=/path/to/android-sdk
   ./gradlew assembleRelease
   ```

   The APK is `app/build/outputs/apk/release/app-release.apk`. It is signed with the debug key,
   for sideloading only.

## Install

Turn on developer options and network debugging on the TV, then:

```sh
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

The first time, the TV asks which app to use as home. Choose this launcher.

`build-and-install.sh` and `build-and-install.ps1` come from the upstream project. They need the
upstream `tools/` folder, which is not in this repository, so use the adb commands above instead.

## Classic home

To get the upstream home screen back, set `terminal_home` to `false` in
`app/src/main/res/values/config.xml` and build again.

## Credits

This is a fork of the launcher in [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin
(MIT License). The terminal home, the trending card and the dock are new here. The rest of the
launcher, including the key handling, app launching and settings, comes from upstream.

Trending data and images come from [TMDB](https://www.themoviedb.org). This product uses the TMDB
API but is not endorsed or certified by TMDB.

Licenses: MIT for the code (see `LICENSE`). The fonts are under the SIL Open Font License 1.1
(`FONT-LICENSE-OFL.txt`, `FONT-LICENSE-OFL-MONO.txt`).
