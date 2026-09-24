# MAA server plugin

A Music Assistant (2.10.x) plugin provider, domain `maa`. It serves whole, seekable,
transcoded track files to the MAA Android Auto app so the app can cache tracks ahead of
playback. Deployment steps are in [../docs/PLUGIN_DEPLOY.md](../docs/PLUGIN_DEPLOY.md).

## HTTP API (MA webserver, port 8095)

Every request needs `Authorization: Bearer <MA token>` from a user with `library.read`.
Error bodies are always JSON: `{"error": "<code>", "message": "..."}`.

| Request | Result |
|---|---|
| `GET /maa/info` | `{"plugin":"maa","version","api":1,"server_id","format","formats","variant","normalization":{"enabled","target"},"cache":{"files","bytes","max_bytes"}}` |
| `GET\|HEAD /maa/track?provider=<library\|prov>&item_id=<id>[&format=opus-<64..320>\|flac-16-44]` | The whole file, with Range/206 support. Headers: `Content-Type` (`audio/ogg` or `audio/flac`), `Accept-Ranges`, `ETag`, `Cache-Control: private, max-age=31536000, immutable`, `X-MAA-Format`, `X-MAA-Source: passthrough\|cached\|transcoded`, `X-MAA-Gain: <dB>`. Errors: 400 `missing_param`/`bad_format`, 401, 403, 404 `not_found`, 503 `provider_unavailable` (`Retry-After: 30`), 503 `transcode_pending` (`Retry-After: 5`, the job keeps running), 502 `transcode_failed`. |
| `POST /maa/prepare` `{"format":"opus-192","tracks":[{"provider":"library","item_id":"123"}]}` | At most 10 tracks. Returns `202 {"queued":n,"ready":m}` and transcodes in the background. More than 10 tracks gets 400 `too_many_tracks`, a malformed body gets 400 `bad_request`. |

## Design notes

- **Transcoding.** MA's `get_media_stream` supplies raw PCM at the source's own rate and bit
  depth. The plugin pipes that PCM into its own `ffmpeg` (niced), which writes
  `<key>.part`. The `.part` file is renamed to `<key>.opus` or `<key>.flac` only when
  ffmpeg exits with code 0. Writing to a real file lets ffmpeg finalize the headers (FLAC
  STREAMINFO/seektable, Ogg granules), which is what makes the result seekable.
- **Volume normalisation.** This mirrors MA's measured (`MEASUREMENT_ONLY`) mode:
  gain = target - integrated loudness, applied in float before the resampler. The loudness
  comes, in order, from the provider, from MA's stored EBU R128 analysis (the same lookup MA
  does), from the plugin's own reading kept in `loudness/`, or else from a two-stage job.
  Stage 1 pipes the PCM into ffmpeg, which writes a float32 WAV (`<key>.measure.wav`, deleted
  afterwards) and runs `ebur128` (integrated loudness and true peak) in the same pass. Stage 2
  encodes the WAV with the gain. Readings are not written back to MA's analysis store: that
  table only serves rows from loaded audio-analysis providers, so the only way in would be to
  impersonate one. A limiter is added only when the gained true peak would exceed the
  ceiling: -1 dBTP for FLAC, and for Opus a lower ceiling that leaves room for codec overshoot
  (1 dB at 160 kbps or more, 1.5 dB from 112 kbps, 2 dB below that). The limiter runs 4x
  oversampled, so it limits true peak. The gain, the loudness source and whether the limiter
  was used are kept in `<key>.json` and served as `X-MAA-Gain`.
- **Passthrough.** When normalisation is off and FLAC is requested, a local FLAC source that
  is at most 16-bit, 44.1 kHz and stereo is served as-is. Its ETag is `pt-<size>-<mtime>`.
- **Jobs.** At most 2 encoders run at once. Jobs are de-duplicated per key and run as
  independent tasks, so they survive client disconnects. A request waits up to 180 s for
  its job.
- **Cache key and fast hits.** The key is `sha1("v2|<provider instance>|<provider item
  id>|<format>|<mapping details>|<variant>")[:32]`. The mapping details are the file mtime for
  filesystem providers. Everything in the key comes from the library's provider mapping,
  plus the normalisation variant, so a cache hit costs one DB lookup, a `stat` and a read of
  the small gain sidecar, and never resolves stream details. When
  a file changes and MA rescans it, the key changes too. The old entry then ages out of
  the cache.
- **LRU.** Serving a file bumps its atime (mtime stays fixed so `Last-Modified` stays
  stable). The trim deletes the least recently used files until the cache is back under
  the configured limit. It never deletes files of running jobs.

## Tests

```sh
python3 -m venv .venv && .venv/bin/pip install -r server/requirements-test.txt
.venv/bin/python -m pytest server/tests
```

`test_formats.py` and `test_transcode.py` need nothing but pytest. `test_transcode.py`
uses a fake `ffmpeg` shim. `test_routes.py` runs the HTTP handlers against a fake MA
instance. It is skipped unless Music Assistant 2.10.x and its dependencies are importable,
for example with an MA 2.10.3 checkout on `PYTHONPATH` in a venv that has MA's
requirements installed.
