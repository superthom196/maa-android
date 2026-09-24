# MAA protocol notes

The contract between the MAA server plugin (`server/maa/`) and the Android app, plus the Music
Assistant facts the app relies on. Verified against Music Assistant 2.10.3 (schema 65).

## Music Assistant (stock server)

- `GET /info` (no auth): `{server_id, server_version, schema_version, base_url, name, ...}`.
  `base_url` is the LAN address only; the Tailscale address has to be entered on the phone.
- `POST /auth/login {"credentials":{"username","password"}}` → `{success, token, user}`.
- `POST /api` with `Authorization: Bearer <token>` and body `{"command","args","message_id"}`
  → the command's result as plain JSON. Stateless: this is what the app uses, not the websocket.
  `auth/token/create {name}` returns a long-lived token, which the app stores.
- Full command list: `GET /api-docs/commands.json`; object shapes: `GET /api-docs/schemas.json`.
- Artwork: `{base}/imageproxy/{proxy_id}?size=N`, N in 80/160/256/512/1024, Bearer token.
- Discovery: mDNS `_mass._tcp`, port 8095.
- MA's own stream routes (`/single/`, `/flow/` on :8097) need a live player-queue session and
  `music/tracks/preview` is 30 s, so none of them can hand a client a whole track. Hence the plugin.

## MAA plugin HTTP API (port 8095)

All routes need `Authorization: Bearer <token>` (session or long-lived). Missing/invalid token →
`401 {"error":"unauthorized"}`; token without library read scope → `403`. Every error body is
`{"error": "<code>", "message": "<text>"}`.

### `GET /maa/info`

```json
{"plugin":"maa","version":"0.1.0","api":1,"server_id":"4d79…","format":"opus-192",
 "formats":["opus-<64..320>","flac-16-44"],
 "variant":"n-14","normalization":{"enabled":true,"target":-14},
 "cache":{"files":12,"bytes":123456,"max_bytes":2147483648}}
```

`variant` names the server's volume normalisation setting: `n<target>` (e.g. `n-14`, `n-17`)
when normalisation is on, `off` when it is off. It is part of the server's cache key, and the app
must add it to its own cache key too. Then a change of the setting makes phones fetch fresh copies
instead of playing stale ones. `normalization` gives the same setting in structured form (`target`
in LUFS, reported even when disabled).

`format` is the one chosen in the MA admin UI (Settings → Providers → MAA). The app requests
that format explicitly on every track URL, so a change on the server never mixes formats in
the phone's cache.

### `GET|HEAD /maa/track?provider=<prov|library>&item_id=<id>&format=<fmt>`

`format` is optional (defaults to the configured one): `opus-<kbps>` (64–320) or `flac-16-44`.

The response is always a finished file (transcoded to disk on the server first), so it has a
real length and supports ranges:

| Header | Value |
|---|---|
| `Content-Type` | `audio/ogg` (Opus) or `audio/flac` |
| `Content-Length`, `Accept-Ranges: bytes`, `ETag`, `Last-Modified` | as for any static file; `Range` → `206` |
| `Cache-Control` | `private, max-age=31536000, immutable` |
| `X-MAA-Format` | the format served |
| `X-MAA-Source` | `passthrough` (source already FLAC ≤16/44.1, served raw; only when normalisation is off), `cached`, `transcoded` |
| `X-MAA-Gain` | normalisation gain applied to this file in dB, 2 decimals, e.g. `-6.30` (`0.00` with normalisation off or for passthrough). Informational |

A cache miss holds the request while the server transcodes (up to 180 s), then answers
`503 {"error":"transcode_pending"}` with `Retry-After: 5` while the job carries on.

| Status | Error | When |
|---|---|---|
| 400 | `missing_param`, `bad_format` | |
| 404 | `not_found` | unknown item, not a track, or no available provider mapping |
| 503 | `provider_unavailable` | `Retry-After: 30` |
| 503 | `transcode_pending` | `Retry-After: 5` |
| 502 | `transcode_failed` | ffmpeg failed |
| 403 | `forbidden` | token lacks library read scope |

`If-None-Match` with the current ETag gets `304`. The server cache key is
`sha1("v2|provider_instance|provider_item_id|format|details|variant")[:32]`, where `details` is
the provider mapping's details field (the file mtime for filesystem providers). A cache hit
therefore costs a library lookup and a `stat`, a rescanned file gets a fresh transcode, and
files of another normalisation variant (or from 0.1.0) are never served.

Volume normalisation works like Music Assistant's own measured normalisation. The gain is
`target - integrated loudness`, and it is applied in float before resampling, dithering and
encoding. The loudness comes, in order, from the provider, from MA's stored EBU R128 analysis,
from the plugin's own earlier reading, or else from a measurement pass. A cache miss on an
unmeasured track therefore does two passes (measure, then encode), about 20–30 s for a 4–5 min
track on the Pi. A limiter is added only when the gained true peak would exceed the ceiling
(-1 dBTP for FLAC; for Opus the ceiling is lower, to leave room for codec overshoot). Output
lands at target ±0.5 LU with a true peak ≤ -1 dBTP. A heavily limited track can end up below
the target.

### `POST /maa/prepare`

Body `{"format":"opus-192","tracks":[{"provider":"library","item_id":"123"}, …]}` (≤10 tracks)
→ `202 {"queued":n,"ready":m}`; a malformed body gets `400 bad_request`, more than 10 tracks
`400 too_many_tracks`. Warms the server's transcode cache for the tracks the phone is
about to download; errors are logged, never returned.

## App-side identifiers

- Queue item URI: `maa://track?provider=P&item_id=I&format=F` (percent-encoded values). Resolved
  to `{current base}/maa/track?…` when the data source opens it, so LAN ↔ Tailscale switches are
  invisible to the queue.
- Cache key: `v1/{serverId}/{provider}/{itemId}/{format}`, each segment percent-encoded. No host.

## Browse ids (Android Auto)

Segments are joined with `/`; each segment is percent-encoded (`URLEncoder`, `+` → `%20`) so
item ids containing `/`, `:` or `%` are safe.

| Id | Children / meaning | Browsable | Playable |
|---|---|---|---|
| `root` | `home`, `artists`, `albums`, `playlists` | ✓ | |
| `home` | `home/recent`, `home/added`, `home/favalbums`, `home/favtracks` | ✓ | |
| `home/recent` | recently played albums/playlists | ✓ | |
| `home/added` | albums by `timestamp_added` desc, 100 | ✓ | |
| `home/favalbums` | favourite albums | ✓ | |
| `home/favtracks` | favourite tracks | ✓ | |
| `artists`, `albums` | the items, or letter buckets `artists/L/{letter}` when > 150 | ✓ | |
| `artist/{prov}/{id}` | the artist's albums (grid) | ✓ | |
| `album/{prov}/{id}` | tracks; playing it plays from track 1 | ✓ | ✓ |
| `playlist/{prov}/{id}` | tracks | ✓ | ✓ |
| `track/{ctxKind}/{ctxProv}/{ctxId}/{prov}/{id}` | a track; `ctxKind` ∈ `album`, `playlist`, `favtracks`, `search`, `single` (`-` for unused ctx segments) | | ✓ |

Playing a track id queues its whole context (album, playlist, favourite tracks) starting at
that track.
