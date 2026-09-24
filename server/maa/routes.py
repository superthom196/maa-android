"""
HTTP API of the MAA plugin, served on the Music Assistant webserver (port 8095).

Dynamic routes on the webserver get no authentication from Music Assistant itself, so every
handler checks the bearer token (or Home Assistant ingress headers) on its own and requires
the library read scope.

Routes (fixed paths, parameters in the query string because item ids can contain "/"):

- ``GET  /maa/info``
- ``GET|HEAD /maa/track?provider=<prov|library>&item_id=<id>[&format=<fmt>]``
- ``POST /maa/prepare`` with ``{"format": "...", "tracks": [{"provider", "item_id"}, ...]}``

Fast cache hits: the cache key is derived from the provider mapping only (provider instance,
provider item id, format name, the mapping's ``details`` - the file mtime for filesystem
providers - and the normalisation variant), so a hit costs one library lookup and a stat (plus
reading the small gain sidecar), and never resolves stream details.
"""

from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from aiohttp import web
from music_assistant_models.auth import Scope
from music_assistant_models.enums import ContentType, MediaType, ProviderType
from music_assistant_models.errors import (
    InvalidDataError,
    MediaNotFoundError,
    MusicAssistantError,
    ProviderUnavailableError,
    ResourceTemporarilyUnavailable,
)
from music_assistant_models.media_items import AudioFormat

from music_assistant.constants import CONF_PROVIDERS
from music_assistant.controllers.streams.audio_analysis import LOUDNESS_ANALYSIS_DOMAIN
from music_assistant.controllers.webserver.helpers.auth_middleware import (
    get_authenticated_user,
    has_scope,
    set_current_user,
)

from .formats import (
    API_VERSION,
    PLUGIN_VERSION,
    SUPPORTED_FORMATS,
    FormatSpec,
    GainPlan,
    Normalization,
    cache_key,
    can_passthrough,
    parse,
    source_key,
)
from .transcode import TranscodeError

if TYPE_CHECKING:
    from collections.abc import AsyncGenerator, Callable

    from music_assistant_models.streamdetails import StreamDetails

    from music_assistant.mass import MusicAssistant
    from music_assistant.models.music_provider import MusicProvider

    from . import MaaProvider

ROUTE_INFO = "/maa/info"
ROUTE_TRACK = "/maa/track"
ROUTE_PREPARE = "/maa/prepare"

# how long a /maa/track request waits for a running transcode before answering 503
TRANSCODE_WAIT = 180
PREPARE_MAX_TRACKS = 10
RETRY_AFTER_PROVIDER = "30"
RETRY_AFTER_PENDING = "5"
CACHE_CONTROL = "private, max-age=31536000, immutable"
WWW_AUTHENTICATE = 'Bearer realm="Music Assistant"'


class ApiError(Exception):
    """An error that maps onto a JSON error response."""

    def __init__(
        self, status: int, code: str, message: str, headers: dict[str, str] | None = None
    ) -> None:
        """Initialize the error."""
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers

    def response(self) -> web.Response:
        """Return the JSON error response."""
        return error_response(self.status, self.code, self.message, self.headers)


def error_response(
    status: int, code: str, message: str, headers: dict[str, str] | None = None
) -> web.Response:
    """Return a JSON error body ``{"error": code, "message": message}``."""
    return web.json_response({"error": code, "message": message}, status=status, headers=headers)


def _provider_unavailable(message: str) -> ApiError:
    return ApiError(503, "provider_unavailable", message, {"Retry-After": RETRY_AFTER_PROVIDER})


def _not_found(message: str) -> ApiError:
    return ApiError(404, "not_found", message)


@dataclass
class ResolvedTrack:
    """A track resolved to the provider instance/item it is streamed from."""

    provider: MusicProvider
    # provider instance id + provider item id + version token form the cache key
    provider_instance: str
    item_id: str
    version: str


class _FixedEtagFileResponse(web.FileResponse):
    """FileResponse that keeps our own ETag instead of aiohttp's mtime/size one."""

    def __init__(self, path: str, etag_value: str, headers: dict[str, str]) -> None:
        super().__init__(path, headers=headers)
        self._maa_etag = etag_value
        web.StreamResponse.etag.fset(self, etag_value)  # type: ignore[attr-defined]

    @property
    def etag(self) -> Any:
        return web.StreamResponse.etag.fget(self)  # type: ignore[attr-defined]

    @etag.setter
    def etag(self, value: Any) -> None:
        # aiohttp assigns its own validator while preparing; keep ours
        fixed = getattr(self, "_maa_etag", value)
        web.StreamResponse.etag.fset(self, fixed)  # type: ignore[attr-defined]


def _etag_matches(header: str | None, etag_value: str) -> bool:
    """Return True if an If-None-Match header matches the (unquoted) etag value."""
    if not header:
        return False
    for raw in header.split(","):
        candidate = raw.strip()
        if candidate == "*":
            return True
        candidate = candidate.removeprefix("W/")
        if candidate == f'"{etag_value}"':
            return True
    return False


async def resolve_track(mass: MusicAssistant, provider: str, item_id: str) -> ResolvedTrack:
    """
    Resolve a (library or provider) track reference to a loaded provider + item id.

    :raises ApiError: not_found (404) or provider_unavailable (503).
    """
    if provider in ("library", "database"):
        try:
            track = await mass.music.tracks.get_library_item(item_id)
        except (MediaNotFoundError, InvalidDataError, ValueError) as err:
            raise _not_found(f"Track {item_id} not found in the library") from err
        mappings = sorted(
            (m for m in track.provider_mappings if m.available),
            key=lambda m: m.quality or 0,
            reverse=True,
        )
        if not mappings:
            raise _not_found(f"Track {item_id} has no available provider mapping")
        for mapping in mappings:
            prov = mass.get_provider(mapping.provider_instance)
            if prov is None or prov.type != ProviderType.MUSIC or not prov.available:
                continue
            return ResolvedTrack(
                provider=prov,  # type: ignore[arg-type]
                provider_instance=mapping.provider_instance,
                item_id=mapping.item_id,
                version=mapping.details or "",
            )
        raise _provider_unavailable(f"No provider for library track {item_id} is loaded")

    prov = mass.get_provider(provider)
    if prov is None:
        configured = mass.config.get(CONF_PROVIDERS, {}) or {}
        known = any(
            provider in (conf.get("instance_id"), conf.get("domain"))
            for conf in configured.values()
            if isinstance(conf, dict)
        )
        if known:
            raise _provider_unavailable(f"Provider {provider} is not available")
        raise _not_found(f"Unknown provider {provider}")
    if prov.type != ProviderType.MUSIC:
        raise _not_found(f"{provider} is not a music provider")
    try:
        track = await mass.music.tracks.get(item_id, prov.instance_id, allow_update_metadata=False)
    except (MediaNotFoundError, InvalidDataError) as err:
        raise _not_found(f"Track {item_id} not found on {provider}") from err
    except (ProviderUnavailableError, ResourceTemporarilyUnavailable) as err:
        raise _provider_unavailable(str(err) or f"Provider {provider} is not available") from err
    own_mapping = next(
        (
            m
            for m in track.provider_mappings
            if m.item_id == item_id and m.provider_instance == prov.instance_id
        ),
        None,
    )
    return ResolvedTrack(
        provider=prov,  # type: ignore[arg-type]
        provider_instance=prov.instance_id,
        item_id=item_id,
        version=(own_mapping.details or "") if own_mapping else "",
    )


def pcm_format_for(streamdetails: StreamDetails) -> AudioFormat:
    """Return the raw PCM format to request from get_media_stream (the source's own)."""
    src = streamdetails.audio_format
    return AudioFormat(
        content_type=ContentType.from_bit_depth(src.bit_depth),
        sample_rate=src.sample_rate,
        bit_depth=src.bit_depth,
        channels=src.channels,
    )


class MaaApi:
    """Request handlers of the MAA HTTP API."""

    def __init__(self, provider: MaaProvider) -> None:
        """Initialize the handlers for the given provider instance."""
        self.provider = provider
        self.mass = provider.mass
        self.logger = provider.logger

    # ------------------------------------------------------------------ registration

    def register(self) -> list[Callable[[], None]]:
        """Register the routes on the webserver; return the unregister callables."""
        webserver = self.mass.webserver
        routes = (
            (ROUTE_INFO, "GET", self.handle_info),
            (ROUTE_TRACK, "GET", self.handle_track),
            (ROUTE_TRACK, "HEAD", self.handle_track),
            (ROUTE_PREPARE, "POST", self.handle_prepare),
        )
        unregister: list[Callable[[], None]] = []
        for path, method, handler in routes:
            # drop a stale registration left behind by an instance that failed to unload
            webserver.unregister_dynamic_route(path, method)
            unregister.append(webserver.register_dynamic_route(path, handler, method))
        return unregister

    # ------------------------------------------------------------------ helpers

    async def _authorize(self, request: web.Request) -> web.Response | None:
        """Return an error response unless the request carries a user with library read."""
        try:
            user = await get_authenticated_user(request)
        except Exception as err:
            self.logger.warning("Authentication error on %s: %s", request.path, err)
            user = None
        if user is None or not user.enabled:
            return error_response(
                401,
                "unauthorized",
                "A valid Music Assistant token is required (Authorization: Bearer <token>)",
                {"WWW-Authenticate": WWW_AUTHENTICATE},
            )
        if not has_scope(user, Scope.LIBRARY_READ):
            return error_response(403, "forbidden", "The library.read scope is required")
        # apply the user's provider restrictions to library lookups, like the API does
        set_current_user(user)
        return None

    def _stream_factory(
        self, streamdetails: StreamDetails, pcm_format: AudioFormat
    ) -> AsyncGenerator[bytes]:
        return self.mass.streams.audio.get_media_stream(
            streamdetails=streamdetails, pcm_format=pcm_format
        )

    def _serve_file(
        self,
        request: web.Request,
        path: str,
        spec: FormatSpec,
        etag_value: str,
        source: str,
        gain_db: float | None = None,
    ) -> web.StreamResponse:
        headers = {
            "Content-Type": spec.mime,
            "Accept-Ranges": "bytes",
            "Cache-Control": CACHE_CONTROL,
            "X-MAA-Format": spec.name,
            "X-MAA-Source": source,
        }
        if gain_db is not None:
            headers["X-MAA-Gain"] = f"{gain_db:.2f}"
        if _etag_matches(request.headers.get("If-None-Match"), etag_value):
            return web.Response(status=304, headers={**headers, "ETag": f'"{etag_value}"'})
        return _FixedEtagFileResponse(path, etag_value, headers)

    async def _serve_cached(
        self, request: web.Request, path: str, spec: FormatSpec, key: str, source: str
    ) -> web.StreamResponse:
        meta = await self.provider.transcode_cache.read_meta(key)
        gain = meta.get("gain_db") if meta else None
        return self._serve_file(
            request,
            path,
            spec,
            key,
            source,
            float(gain) if isinstance(gain, (int, float)) else None,
        )

    async def _gain_plan(
        self, norm: Normalization, resolved: ResolvedTrack, streamdetails: StreamDetails
    ) -> GainPlan:
        """
        Return what is known about the track's loudness, in Music Assistant's order.

        The provider's own value first, then MA's stored EBU R128 analysis (the lookup MA
        does before playback); without either the job measures the track itself.
        """
        if not norm.enabled:
            return GainPlan()
        src_key = source_key(resolved.provider_instance, resolved.item_id, resolved.version)
        if streamdetails.loudness is not None:
            return GainPlan(
                normalize=True,
                target=norm.target,
                loudness=float(streamdetails.loudness),
                loudness_source="provider",
                source_key=src_key,
            )
        try:
            analysis = await self.mass.streams.audio_analysis.get_audio_analysis(
                streamdetails.item_id,
                streamdetails.provider,
                media_type=MediaType.TRACK,
                priority=(LOUDNESS_ANALYSIS_DOMAIN,),
            )
        except Exception as err:
            self.logger.debug("Audio analysis lookup failed for %s: %s", resolved.item_id, err)
            analysis = None
        if analysis is not None and analysis.loudness_integrated is not None:
            return GainPlan(
                normalize=True,
                target=norm.target,
                loudness=round(float(analysis.loudness_integrated), 2),
                true_peak=analysis.true_peak,
                loudness_source="analysis",
                source_key=src_key,
            )
        return GainPlan(normalize=True, target=norm.target, source_key=src_key)

    async def _start_or_passthrough(
        self, resolved: ResolvedTrack, spec: FormatSpec, key: str, norm: Normalization
    ) -> tuple[asyncio.Task[str] | None, str | None]:
        """
        Resolve stream details for a miss: return (job, None) or (None, passthrough path).

        Passthrough is only possible with normalisation off: a raw master served as-is would
        defeat it.
        """
        streamdetails = await self._get_stream_details(resolved)
        audio_format = streamdetails.audio_format
        if not norm.enabled and can_passthrough(
            spec,
            audio_format.content_type,
            audio_format.bit_depth,
            audio_format.sample_rate,
            audio_format.channels,
            streamdetails.stream_type,
            streamdetails.path,
        ):
            # the path comes from the provider's stream details, never from the request
            return None, str(streamdetails.path)
        plan = await self._gain_plan(norm, resolved, streamdetails)
        task = self.provider.transcode_cache.ensure_job(
            key,
            spec,
            streamdetails,
            pcm_format_for(streamdetails),
            self._stream_factory,
            plan,
        )
        return task, None

    async def _get_stream_details(self, resolved: ResolvedTrack) -> StreamDetails:
        try:
            return await resolved.provider.get_stream_details(resolved.item_id, MediaType.TRACK)
        except MediaNotFoundError as err:
            raise _not_found(str(err) or "Track not found") from err
        except (ProviderUnavailableError, ResourceTemporarilyUnavailable) as err:
            raise _provider_unavailable(str(err) or "Provider unavailable") from err
        except MusicAssistantError as err:
            raise ApiError(502, "transcode_failed", f"Could not get stream details: {err}") from err

    # ------------------------------------------------------------------ handlers

    async def handle_info(self, request: web.Request) -> web.StreamResponse:
        """Return plugin/server info and cache statistics."""
        if (denied := await self._authorize(request)) is not None:
            return denied
        cache = self.provider.transcode_cache
        stats = await cache.stats()
        norm = self.provider.normalization
        return web.json_response(
            {
                "plugin": "maa",
                "version": PLUGIN_VERSION,
                "api": API_VERSION,
                "server_id": self.mass.server_id,
                "format": self.provider.configured_format.name,
                "formats": list(SUPPORTED_FORMATS),
                "variant": norm.variant,
                "normalization": {"enabled": norm.enabled, "target": norm.target},
                "cache": {
                    "files": stats["files"],
                    "bytes": stats["bytes"],
                    "max_bytes": cache.max_bytes,
                },
            }
        )

    async def handle_track(self, request: web.Request) -> web.StreamResponse:
        """Serve a whole, seekable track file in the requested format."""
        if (denied := await self._authorize(request)) is not None:
            return denied
        provider = request.query.get("provider", "").strip()
        item_id = request.query.get("item_id", "")
        if not provider or not item_id:
            return error_response(400, "missing_param", "provider and item_id are required")
        fmt = request.query.get("format")
        spec = parse(fmt) if fmt else self.provider.configured_format
        if spec is None:
            return error_response(
                400, "bad_format", f"Unsupported format {fmt!r}, use one of {SUPPORTED_FORMATS}"
            )
        cache = self.provider.transcode_cache
        norm = self.provider.normalization
        try:
            resolved = await resolve_track(self.mass, provider, item_id)
            key = cache_key(
                resolved.provider_instance,
                resolved.item_id,
                spec.name,
                resolved.version,
                norm.variant,
            )

            # fast path: finished file, no stream details needed
            if (path := await cache.lookup(key, spec)) is not None:
                return await self._serve_cached(request, path, spec, key, "cached")

            if (task := cache.pending(key)) is None:
                task, source_path = await self._start_or_passthrough(resolved, spec, key, norm)
                if source_path is not None:
                    try:
                        st = await asyncio.to_thread(os.stat, source_path)
                    except OSError as err:
                        raise _not_found(f"Source file is not accessible: {err}") from err
                    etag_value = f"pt-{st.st_size}-{int(st.st_mtime)}"
                    return self._serve_file(
                        request, source_path, spec, etag_value, "passthrough", 0.0
                    )
            assert task is not None
        except ApiError as err:
            return err.response()
        except TranscodeError as err:
            # only raised while the plugin is shutting down
            return error_response(
                503, "provider_unavailable", str(err), {"Retry-After": RETRY_AFTER_PROVIDER}
            )

        try:
            path = await asyncio.wait_for(asyncio.shield(task), timeout=TRANSCODE_WAIT)
        except TimeoutError:
            return error_response(
                503,
                "transcode_pending",
                f"Transcode still running after {TRANSCODE_WAIT}s, retry later",
                {"Retry-After": RETRY_AFTER_PENDING},
            )
        except asyncio.CancelledError:
            current = asyncio.current_task()
            if current is not None and current.cancelling():
                raise
            # the job itself was cancelled (plugin reloading/unloading)
            return error_response(
                503,
                "provider_unavailable",
                "The transcode was cancelled, retry later",
                {"Retry-After": RETRY_AFTER_PROVIDER},
            )
        except Exception as err:
            self.logger.warning("Transcode of %s/%s failed: %s", provider, item_id, err)
            return error_response(502, "transcode_failed", str(err) or type(err).__name__)
        return await self._serve_cached(request, path, spec, key, "transcoded")

    async def handle_prepare(self, request: web.Request) -> web.StreamResponse:
        """Queue background transcodes for up to 10 tracks."""
        if (denied := await self._authorize(request)) is not None:
            return denied
        try:
            body = await request.json()
        except Exception:
            return error_response(400, "bad_request", "Body must be a JSON object")
        if not isinstance(body, dict):
            return error_response(400, "bad_request", "Body must be a JSON object")
        fmt = body.get("format")
        spec = parse(fmt) if fmt else self.provider.configured_format
        if spec is None:
            return error_response(
                400, "bad_format", f"Unsupported format {fmt!r}, use one of {SUPPORTED_FORMATS}"
            )
        tracks = body.get("tracks")
        if not isinstance(tracks, list):
            return error_response(400, "bad_request", "tracks must be a list")
        if len(tracks) > PREPARE_MAX_TRACKS:
            return error_response(
                400, "too_many_tracks", f"At most {PREPARE_MAX_TRACKS} tracks per request"
            )
        refs: list[tuple[str, str]] = []
        for entry in tracks:
            if (
                not isinstance(entry, dict)
                or not isinstance(entry.get("provider"), str)
                or not entry.get("provider")
                or entry.get("item_id") in (None, "")
            ):
                return error_response(
                    400, "bad_request", "Each track needs a provider and an item_id"
                )
            refs.append((entry["provider"], str(entry["item_id"])))
        results = await asyncio.gather(
            *(self._prepare_one(spec, provider, item_id) for provider, item_id in refs)
        )
        return web.json_response(
            {
                "queued": sum(1 for r in results if r == "queued"),
                "ready": sum(1 for r in results if r == "ready"),
            },
            status=202,
        )

    async def _prepare_one(self, spec: FormatSpec, provider: str, item_id: str) -> str | None:
        """Start the transcode for one track; return 'ready', 'queued' or None on error."""
        cache = self.provider.transcode_cache
        norm = self.provider.normalization
        try:
            resolved = await resolve_track(self.mass, provider, item_id)
            key = cache_key(
                resolved.provider_instance,
                resolved.item_id,
                spec.name,
                resolved.version,
                norm.variant,
            )
            if await cache.lookup(key, spec) is not None:
                return "ready"
            if cache.pending(key) is not None:
                return "queued"
            task, _ = await self._start_or_passthrough(resolved, spec, key, norm)
            return "queued" if task is not None else "ready"
        except ApiError as err:
            self.logger.info("Prepare %s/%s skipped: %s", provider, item_id, err.message)
        except Exception as err:
            self.logger.warning("Prepare %s/%s failed: %s", provider, item_id, err)
        return None
