#!/usr/bin/env bash
# Verify a deployed MAA plugin end-to-end over HTTP.
#
# Usage: scripts/verify-plugin.sh BASE TOKEN [PROVIDER ITEM_ID]
#   BASE      Music Assistant webserver URL, e.g. http://serverpi:8095
#   TOKEN     a Music Assistant token (long-lived token from Settings -> Profile).
#             Pass "-" to read it from $MA_TOKEN instead (keeps it out of shell history).
#   PROVIDER ITEM_ID  track to test with; default: the first library track
#             (provider "library"), found via the JSON-RPC API.
#
# Env: MAA_FORMAT (optional) format to request, e.g. opus-192 or flac-16-44
#      (default: the format configured in the plugin).
#
# The token is only ever passed to curl through a private temp header file; it is never
# printed. Exits non-zero when any check fails.

set -uo pipefail

if [[ $# -lt 2 || $# -eq 3 || $# -gt 4 ]]; then
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

BASE="${1%/}"
TOKEN="$2"
if [[ "$TOKEN" == "-" ]]; then
  TOKEN="${MA_TOKEN:-}"
fi
if [[ -z "$TOKEN" ]]; then
  echo "error: empty token" >&2
  exit 2
fi
PROVIDER="${3:-}"
ITEM_ID="${4:-}"
FORMAT="${MAA_FORMAT:-}"

for tool in curl python3; do
  command -v "$tool" >/dev/null || { echo "error: $tool is required" >&2; exit 2; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
AUTH_HEADER="$WORK/auth.hdr"
(umask 077; printf 'Authorization: Bearer %s\n' "$TOKEN" > "$AUTH_HEADER")
unset TOKEN MA_TOKEN

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$*"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$*"; }
check() { # check <description> <condition...>
  local desc="$1"; shift
  if "$@"; then pass "$desc"; else fail "$desc"; fi
}

# urlencode <string>
urlencode() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

# header <headers-file> <name>  -> value of the last matching header (case-insensitive)
header() {
  tr -d '\r' < "$1" | awk -v name="$2" '
    BEGIN { lname = tolower(name) ":" }
    tolower(substr($0, 1, length(lname))) == lname { v = substr($0, length(lname) + 1); sub(/^[ \t]+/, "", v); val = v }
    END { print val }'
}

# req <name> <curl args...> : runs curl, stores status in $STATUS, headers in $WORK/<name>.h,
# body in $WORK/<name>.b and total time in $TIME
req() {
  local name="$1"; shift
  local out
  out="$(curl -sS -o "$WORK/$name.b" -D "$WORK/$name.h" -w '%{http_code} %{time_total}' "$@" 2>"$WORK/$name.err")" || true
  STATUS="${out%% *}"
  TIME="${out##* }"
  [[ -n "$STATUS" ]] || STATUS="000"
}

json_field() { # json_field <file> <python expression on d>
  python3 -c 'import json, sys; d = json.load(open(sys.argv[1])); print(eval(sys.argv[2]))' "$1" "$2" 2>/dev/null
}

echo "MAA plugin verification against $BASE"

# ---------------------------------------------------------------- /maa/info
echo "[info]"
req info_noauth "$BASE/maa/info"
check "GET /maa/info without token -> 401 (got $STATUS)" [ "$STATUS" = "401" ]
check "401 carries WWW-Authenticate" [ -n "$(header "$WORK/info_noauth.h" WWW-Authenticate)" ]

req info "$BASE/maa/info" -H "@$AUTH_HEADER"
check "GET /maa/info with token -> 200 (got $STATUS)" [ "$STATUS" = "200" ]
if [[ "$STATUS" == "200" ]]; then
  echo "        plugin=$(json_field "$WORK/info.b" 'd["plugin"]') version=$(json_field "$WORK/info.b" 'd["version"]') api=$(json_field "$WORK/info.b" 'd["api"]') format=$(json_field "$WORK/info.b" 'd["format"]') cache=$(json_field "$WORK/info.b" 'd["cache"]')"
  check "info reports plugin=maa, api=1" [ "$(json_field "$WORK/info.b" 'd["plugin"] + "/" + str(d["api"])')" = "maa/1" ]
else
  echo "        cannot continue without a working /maa/info (is the plugin enabled?)"
  echo "RESULT: $PASS passed, $FAIL failed"
  exit 1
fi

# ---------------------------------------------------------------- pick a track
if [[ -z "$PROVIDER" ]]; then
  req pick "$BASE/api" -H "@$AUTH_HEADER" -H 'Content-Type: application/json' \
    --data '{"message_id":"maa-verify","command":"music/tracks/library_items","args":{"limit":1}}'
  ITEM_ID="$(python3 - "$WORK/pick.b" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
if isinstance(d, dict):
    d = d.get("result", d.get("items", d))
if isinstance(d, dict):
    d = d.get("items", [])
print(d[0]["item_id"] if d else "")
PY
)"
  PROVIDER="library"
  if [[ -z "$ITEM_ID" ]]; then
    fail "could not pick a library track via /api (HTTP $STATUS)"
    echo "RESULT: $PASS passed, $FAIL failed"
    exit 1
  fi
fi
echo "[track] provider=$PROVIDER item_id=$ITEM_ID${FORMAT:+ format=$FORMAT}"
QUERY="provider=$(urlencode "$PROVIDER")&item_id=$(urlencode "$ITEM_ID")"
[[ -n "$FORMAT" ]] && QUERY="$QUERY&format=$(urlencode "$FORMAT")"
URL="$BASE/maa/track?$QUERY"

req track_noauth "$URL" -I
check "HEAD track without token -> 401 (got $STATUS)" [ "$STATUS" = "401" ]

# ---------------------------------------------------------------- HEAD (may trigger a transcode)
for attempt in $(seq 1 20); do
  req head "$URL" -I -H "@$AUTH_HEADER" --max-time 240
  if [[ "$STATUS" == "503" ]]; then
    wait_s="$(header "$WORK/head.h" Retry-After)"
    echo "        503 (attempt $attempt), retrying in ${wait_s:-5}s"
    sleep "${wait_s:-5}"
    continue
  fi
  break
done
check "HEAD track -> 200 (got $STATUS, ${TIME}s, source=$(header "$WORK/head.h" X-MAA-Source))" [ "$STATUS" = "200" ]
LENGTH="$(header "$WORK/head.h" Content-Length)"
ETAG="$(header "$WORK/head.h" ETag)"
check "HEAD has Content-Length ($LENGTH)" [ -n "$LENGTH" ]
check "HEAD has ETag ($ETAG)" [ -n "$ETAG" ]
check "HEAD has Accept-Ranges: bytes" [ "$(header "$WORK/head.h" Accept-Ranges)" = "bytes" ]
check "HEAD has Content-Type audio/* ($(header "$WORK/head.h" Content-Type))" \
  [ "$(header "$WORK/head.h" Content-Type | cut -d/ -f1)" = "audio" ]
check "HEAD has X-MAA-Format ($(header "$WORK/head.h" X-MAA-Format))" [ -n "$(header "$WORK/head.h" X-MAA-Format)" ]

# ---------------------------------------------------------------- Range
req range "$URL" -H "@$AUTH_HEADER" -H 'Range: bytes=0-99'
RANGE_SIZE="$(wc -c < "$WORK/range.b" | tr -d ' ')"
WANT=100
[[ "$LENGTH" =~ ^[0-9]+$ && "$LENGTH" -lt 100 ]] && WANT="$LENGTH"
check "Range bytes=0-99 -> 206 (got $STATUS)" [ "$STATUS" = "206" ]
check "Range body is $WANT bytes (got $RANGE_SIZE)" [ "$RANGE_SIZE" = "$WANT" ]
check "Content-Range is set ($(header "$WORK/range.h" Content-Range))" \
  [ "$(header "$WORK/range.h" Content-Range)" = "bytes 0-$((WANT - 1))/$LENGTH" ]

# ---------------------------------------------------------------- full download
req full "$URL" -H "@$AUTH_HEADER" --max-time 600
SOURCE="$(header "$WORK/full.h" X-MAA-Source)"
SIZE="$(wc -c < "$WORK/full.b" | tr -d ' ')"
check "GET track -> 200 (got $STATUS, ${TIME}s, source=$SOURCE)" [ "$STATUS" = "200" ]
check "downloaded size matches Content-Length ($SIZE / $LENGTH)" [ "$SIZE" = "$LENGTH" ]
check "ETag is stable ($(header "$WORK/full.h" ETag))" [ "$(header "$WORK/full.h" ETag)" = "$ETAG" ]
if command -v ffprobe >/dev/null; then
  PROBE="$(ffprobe -v error -show_entries stream=codec_name,sample_rate,channels:format=duration \
    -of default=nw=1 "$WORK/full.b" 2>&1 | tr '\n' ' ')"
  probe_ok() { [[ "$PROBE" =~ codec_name=(opus|flac) && "$PROBE" =~ duration=[0-9] ]]; }
  check "ffprobe: $PROBE" probe_ok
else
  echo "        (ffprobe not installed, skipping codec/duration check)"
fi

# ---------------------------------------------------------------- second request (cache hit)
req again "$URL" -H "@$AUTH_HEADER"
AGAIN_SOURCE="$(header "$WORK/again.h" X-MAA-Source)"
if [[ "$SOURCE" == "passthrough" ]]; then
  check "second GET -> passthrough again (${TIME}s)" [ "$AGAIN_SOURCE" = "passthrough" ]
else
  check "second GET -> X-MAA-Source cached (got $AGAIN_SOURCE, ${TIME}s)" [ "$AGAIN_SOURCE" = "cached" ]
fi

req inm "$URL" -H "@$AUTH_HEADER" -H "If-None-Match: $ETAG"
check "If-None-Match with the ETag -> 304 (got $STATUS)" [ "$STATUS" = "304" ]

# ---------------------------------------------------------------- errors
echo "[errors]"
req badfmt "$BASE/maa/track?provider=$(urlencode "$PROVIDER")&item_id=$(urlencode "$ITEM_ID")&format=mp3-320" \
  -H "@$AUTH_HEADER"
check "bad format -> 400 bad_format (got $STATUS $(json_field "$WORK/badfmt.b" 'd["error"]'))" \
  [ "$STATUS" = "400" ]
req badid "$BASE/maa/track?provider=library&item_id=987654321" -H "@$AUTH_HEADER"
check "unknown library id -> 404 not_found (got $STATUS $(json_field "$WORK/badid.b" 'd["error"]'))" \
  [ "$STATUS" = "404" ]
req missing "$BASE/maa/track?provider=library" -H "@$AUTH_HEADER"
check "missing item_id -> 400 missing_param (got $STATUS)" [ "$STATUS" = "400" ]
req prepare "$BASE/maa/prepare" -H "@$AUTH_HEADER" -H 'Content-Type: application/json' \
  --data "{\"tracks\":[{\"provider\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$PROVIDER"),\"item_id\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$ITEM_ID")}]}"
check "POST /maa/prepare -> 202 (got $STATUS $(cat "$WORK/prepare.b" 2>/dev/null))" [ "$STATUS" = "202" ]

echo "RESULT: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
