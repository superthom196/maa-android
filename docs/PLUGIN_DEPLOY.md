# Deploying the MAA server plugin

The plugin (`server/maa/`) runs inside the Music Assistant container on `serverpi`.
It is mounted read-only into MA's `providers/` package, the same way the Crate provider is.
MA is never rebuilt and nothing is copied into the container.

| What | Where |
|---|---|
| MA container | `music-assistant` (image `ghcr.io/music-assistant/server:2.10.3`, host network, webserver port 8095) |
| Compose file | `/home/thom/docker_data/docker-compose.yml` |
| Checkout on the Pi | `/home/thom/maa-android` (non-bare git repo, pushed to from the Mac) |
| Plugin inside the container | `/app/venv/lib/python3.14/site-packages/music_assistant/providers/maa` |
| Transcode cache | `/data/.cache/maa` in the container = `/home/thom/docker_data/music-assistant/.cache/maa` on the host |

## 1. One-time: create the repo on the Pi

On serverpi:

```sh
git init /home/thom/maa-android
git -C /home/thom/maa-android config receive.denyCurrentBranch updateInstead
```

`updateInstead` makes a push update the checked-out working tree, so the mounted
directory always holds what was pushed (the same setup `/home/thom/crate` uses).

On the Mac, in this repo:

```sh
git remote add serverpi serverpi:/home/thom/maa-android
git push serverpi main
```

Check: `ssh serverpi ls /home/thom/maa-android/server/maa` lists `__init__.py`,
`manifest.json`, `formats.py`, `routes.py`, `transcode.py`, `strings.json` and `icon.svg`.

## 2. One-time: add the bind mount

On serverpi, back up the compose file first:

```sh
cd /home/thom/docker_data
cp docker-compose.yml docker-compose.yml.bak-maa-$(date +%F)
```

In the `music-assistant` service, add this line to `volumes:` directly below the Crate line
(`.../providers/crate:ro`):

```yaml
      # MAA plugin (Android Auto offline cache), read-only bind mount; same python3.14
      # caveat as the Crate line above.
      - /home/thom/maa-android/server/maa:/app/venv/lib/python3.14/site-packages/music_assistant/providers/maa:ro
```

Validate the file and recreate only the MA container. Never run `down`, it would stop
every other service in the file:

```sh
docker compose -f /home/thom/docker_data/docker-compose.yml config --quiet && echo compose-ok
docker compose -f /home/thom/docker_data/docker-compose.yml up -d music-assistant
```

## 3. Check that MA picked it up

```sh
docker exec music-assistant ls /app/venv/lib/python3.14/site-packages/music_assistant/providers/maa
docker logs --since 5m music-assistant 2>&1 | grep -i -E "maa|error" | tail -20
```

The mount is read-only, so Python cannot write `__pycache__` there. That is harmless:
the module is compiled in memory on every start.

## 4. Enable and configure in the MA UI

1. Go to Settings → Providers → Add provider, then pick **MAA (Android Auto offline cache)**.
   The alpha badge is expected.
2. Options:
   - **Output format**: Opus (Ogg), the default, or FLAC 16-bit / 44.1 kHz. CD-quality FLAC
     sources are served untouched in FLAC mode when normalisation is off.
   - **Opus bitrate**: 64–320 kbps, default 192. Only shown for Opus.
   - **Volume normalisation**: on by default. Brings every track to the same loudness,
     like MA's own players do. It uses MA's loudness analysis when there is one; otherwise
     the first request for a track measures it in an extra pass. Readings are kept in
     `/data/.cache/maa/loudness/`. A limiter is only added when a boosted track would peak
     above -1 dBTP. With normalisation on, CD-quality FLAC is not passed through untouched.
   - **Target loudness (LUFS)**: defaults to MA's global normalisation target (Settings →
     Core → Streams → volume normalisation target). In MA 2.10.3 that is -14 unless it has
     been changed. The range is -30 to -6.
   - **Maximum cache size (GB)**: 1–50, default 2. When the cache grows past this, the least
     recently served files are deleted first.
   - **Clear transcode cache**: deletes every cached file. Transcodes that are still
     running are not affected. Stored loudness readings are cleared too.
3. When enabled, the log shows
   `MAA plugin ready: format opus-192, normalisation n-14, cache /data/.cache/maa (max 2 GB)`.

Changing the normalisation setting changes the `variant`, which is part of every cache key.
Files cached under the old setting are never served again and age out of the cache (or clear
them with the button). The app puts the `variant` from `/maa/info` into its own cache key, so
phones re-download too. The upgrade from 0.1.0 works the same way: every 0.1.0 file becomes
unreachable, so press **Clear transcode cache** once after upgrading to free the space
straight away.

Changing any option reloads the plugin. Running transcodes are cancelled and their partial
files deleted, and the next request starts them again.

## 5. Verify end-to-end

On the Mac, with a long-lived token from MA (Settings → Profile → Tokens):

```sh
MA_TOKEN=... scripts/verify-plugin.sh http://serverpi:8095 -
MA_TOKEN=... MAA_FORMAT=flac-16-44 scripts/verify-plugin.sh http://serverpi:8095 - library <item_id>
```

The script checks that requests without auth get 401, then `/maa/info`, a HEAD, a
`Range: bytes=0-99` request (expects 206), a full download (probed with ffprobe when it is
installed), a second request (expects a cache hit), 304 on `If-None-Match`, 400 and 404
errors, and `/maa/prepare`. It never prints the token, and it exits non-zero if any check
fails. The first request for a track waits for the transcode (about 1–2 s per minute of
hi-res FLAC on the Pi 4). If a transcode takes longer than 180 s, the plugin answers
`503 transcode_pending` with `Retry-After: 5`, and the script retries.

## Updating the plugin

```sh
git push serverpi main                                   # on the Mac
ssh serverpi docker restart music-assistant              # MA loads providers only at start
```

If an update changes the compose file, run `up -d music-assistant` instead of the restart.
Cached files survive a restart. If the cache key scheme ever changes (the `v1` prefix in
`formats.cache_key`), old files are never served again. The LRU trim removes them over
time, or you can clear them with the **Clear transcode cache** button.

## After an MA image upgrade

The mount target embeds the Python minor version (`python3.14`). After upgrading the MA
image, find the providers directory again:

```sh
docker exec music-assistant python -c "import music_assistant, os; print(os.path.dirname(music_assistant.__file__) + '/providers')"
```

If the path changed, update both the Crate line and the MAA line in the compose file, then run
`up -d music-assistant`. The plugin was written and tested against MA 2.10.3 (models
1.1.205). If MA internals it imports have moved, loading fails with a clear log line:
`MAA plugin: importing Music Assistant internals failed (...)`. The other providers keep
working.

## Rollback

Disable or remove the provider in the MA UI, then delete the MAA line from the compose file
(or restore `docker-compose.yml.bak-maa-<date>`) and run `up -d music-assistant`. Removing
the provider in the UI also deletes `/data/.cache/maa`.
