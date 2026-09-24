# MAA

**M**usic **A**ssistant **A**uto — plays your [Music Assistant](https://music-assistant.io)
library in Android Auto, **on the phone**, with whole tracks downloaded ahead of time. When the
car drives through a dead zone the music keeps going: the current track and the next few are
already on the phone.

The official Music Assistant app streams to the phone in real time and stops as soon as the
mobile network does. MAA instead:

1. asks a small server plugin (`server/maa/`) for each track as a finished file — Opus or
   FLAC 16/44.1, chosen in the Music Assistant admin UI;
2. keeps the current track plus the next *N* (default 3) fully downloaded on the phone;
3. plays from that cache, and catches up as soon as the network is back.

## Pieces

| Part | Where | What it does |
|---|---|---|
| Server plugin | `server/maa/` | Music Assistant plugin provider: `/maa/info`, `/maa/track`, `/maa/prepare` on port 8095. See [docs/PLUGIN_DEPLOY.md](docs/PLUGIN_DEPLOY.md). |
| Android app | `app/` | Android Auto media app (Media3 `MediaLibraryService` + ExoPlayer + look-ahead cache), with a small phone UI to connect, sign in and set things up. |

Protocol and ids: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Reaching the server from the car

The phone needs a route to the server over mobile data. MAA stores two addresses for the same
server — the LAN one (found by discovery) and a remote one, e.g. the server's Tailscale address
— and uses whichever answers, checking the `server_id` so it never talks to the wrong box.

## Build from source

```bash
./build.sh                  # assembleRelease
PHONE=<serial> ./build.sh install
```

`build.sh` uses Android Studio's bundled JDK. Requires the Android SDK with Platform 37 and
Build-Tools 36. The release build is minified and, unless a signing key is configured
(`signing.properties` or the `MAA_KEYSTORE_*` environment variables), debug-signed.

Requires Android 9 (API 28) or later and Music Assistant 2.10.x with the MAA plugin enabled.

## Releases

The app and the plugin share one version, taken from the `vX.Y.Z` tag (`PLUGIN_VERSION` in
`server/maa/formats.py` must match). Releases are built and signed locally with
`signing.properties`:

```bash
git tag -a vX.Y.Z -m "MAA X.Y.Z" && ./build.sh
cp app/build/outputs/apk/release/app-release.apk maa-X.Y.Z.apk
git archive --format=zip --prefix=maa/ -o maa-plugin-X.Y.Z.zip vX.Y.Z:server/maa
git push origin vX.Y.Z
gh release create vX.Y.Z --title "MAA X.Y.Z" maa-X.Y.Z.apk maa-plugin-X.Y.Z.zip
```

The Release workflow skips tags unless the repo has the `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets. With them, it builds the signed
APK on CI and attaches it to the tag's release.

## License

GPL-3.0, see [LICENSE](LICENSE).
