"""
HTTP tests for maa.routes against a fake Music Assistant instance.

These need Music Assistant 2.10.x (and its dependencies) importable, so they are skipped in a
plain environment. Run them with the MA source on PYTHONPATH, e.g.::

    PYTHONPATH=/path/to/checkout python -m pytest server/tests

The handlers run for real (auth helpers, aiohttp FileResponse, TranscodeCache with the fake
ffmpeg from conftest); only the MA controllers they call are faked.
"""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

pytest.importorskip("music_assistant.controllers.webserver.helpers.auth_middleware")

from aiohttp import web  # noqa: E402
from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from music_assistant_models.auth import User  # noqa: E402
from music_assistant_models.enums import ContentType, ProviderType, StreamType  # noqa: E402
from music_assistant_models.errors import MediaNotFoundError  # noqa: E402
from music_assistant_models.media_items import AudioFormat  # noqa: E402
from music_assistant_models.streamdetails import StreamDetails  # noqa: E402

from conftest import ffmpeg_calls  # noqa: E402
from maa import routes  # noqa: E402
from maa.formats import FormatSpec, Normalization, cache_key, limiter_filters  # noqa: E402
from maa.transcode import TranscodeCache  # noqa: E402

TOKEN = "good-token"
WEAK_TOKEN = "no-scope-token"
PCM_CHUNKS = [b"pcm1", b"pcm2"]


class FakeProvider:
    """A loaded music provider."""

    type = ProviderType.MUSIC
    available = True

    def __init__(self, instance_id: str, sd_factory: Any) -> None:
        self.instance_id = instance_id
        self.domain = instance_id.split("--")[0]
        self._sd_factory = sd_factory
        self.stream_details_calls = 0

    async def get_stream_details(self, item_id: str, media_type: Any) -> StreamDetails:
        self.stream_details_calls += 1
        return self._sd_factory(item_id)


class FakeTracks:
    def __init__(self, library: dict[str, Any]) -> None:
        self.library = library

    async def get_library_item(self, item_id: str) -> Any:
        db_id = int(item_id)
        if str(db_id) not in self.library:
            raise MediaNotFoundError(f"track not found in library: {db_id}")
        return self.library[str(db_id)]

    async def get(self, item_id: str, provider: str, allow_update_metadata: bool = True) -> Any:
        for track in self.library.values():
            for mapping in track.provider_mappings:
                if mapping.item_id == item_id and mapping.provider_instance == provider:
                    return track
        raise MediaNotFoundError(item_id)


class FakeWebserver:
    def __init__(self) -> None:
        self.routes: dict[str, Any] = {}

        async def authenticate_with_token(token: str) -> User | None:
            if token == TOKEN:
                return User(user_id="u1", username="app", role="user")
            if token == WEAK_TOKEN:
                return User(user_id="u2", username="weak", role="no-such-role")
            return None

        self.auth = SimpleNamespace(authenticate_with_token=authenticate_with_token)

    def register_dynamic_route(self, path: str, handler: Any, method: str = "*") -> Any:
        key = f"{method}.{path}"
        assert key not in self.routes
        self.routes[key] = handler
        return lambda: self.routes.pop(key, None)

    def unregister_dynamic_route(self, path: str, method: str = "*") -> None:
        self.routes.pop(f"{method}.{path}", None)

    async def catch_all(self, request: web.Request) -> web.StreamResponse:
        for key in (f"{request.method}.{request.path}", f"*.{request.path}"):
            if handler := self.routes.get(key):
                return await handler(request)
        return web.Response(status=404)


def mapping(instance: str, item_id: str, details: str = "1700000000") -> Any:
    return SimpleNamespace(
        available=True, quality=10, provider_instance=instance, item_id=item_id, details=details
    )


@pytest.fixture
def env(tmp_path: Path, fake_ffmpeg: Path) -> dict[str, Any]:
    cd_file = tmp_path / "cd.flac"
    cd_file.write_bytes(b"fLaC-cd-quality-file")

    def sd_factory(item_id: str) -> StreamDetails:
        if item_id == "cd.flac":
            return StreamDetails(
                provider="filesystem_local--x",
                item_id=item_id,
                audio_format=AudioFormat(
                    content_type=ContentType.FLAC, sample_rate=44100, bit_depth=16, channels=2
                ),
                stream_type=StreamType.LOCAL_FILE,
                path=str(cd_file),
            )
        return StreamDetails(
            provider="filesystem_local--x",
            item_id=item_id,
            audio_format=AudioFormat(
                content_type=ContentType.FLAC, sample_rate=96000, bit_depth=24, channels=2
            ),
            stream_type=StreamType.LOCAL_FILE,
            path=f"/nonexistent/{item_id}",
        )

    prov = FakeProvider("filesystem_local--x", sd_factory)
    library = {
        "1": SimpleNamespace(provider_mappings=[mapping(prov.instance_id, "hires.flac")]),
        "2": SimpleNamespace(provider_mappings=[mapping(prov.instance_id, "cd.flac")]),
        "3": SimpleNamespace(provider_mappings=[mapping("gone--y", "x.flac")]),
    }
    streamed: list[Any] = []

    def get_media_stream(streamdetails: StreamDetails, pcm_format: AudioFormat) -> Any:
        streamed.append((streamdetails, pcm_format))

        async def gen() -> Any:
            for chunk in PCM_CHUNKS:
                await asyncio.sleep(0)
                yield chunk

        return gen()

    analyses: dict[str, Any] = {}

    async def get_audio_analysis(
        item_id: str, provider: str, media_type: Any = None, priority: Any = None
    ) -> Any:
        assert priority == ("loudness_analysis",)
        return analyses.get(item_id)

    webserver = FakeWebserver()
    mass = SimpleNamespace(
        server_id="server-123",
        webserver=webserver,
        config=SimpleNamespace(get=lambda key, default=None: {"gone": {"domain": "gone"}}),
        music=SimpleNamespace(tracks=FakeTracks(library)),
        get_provider=lambda name: prov if name in (prov.instance_id, prov.domain) else None,
        streams=SimpleNamespace(
            audio=SimpleNamespace(get_media_stream=get_media_stream),
            audio_analysis=SimpleNamespace(get_audio_analysis=get_audio_analysis),
        ),
    )
    return {
        "mass": mass,
        "prov": prov,
        "streamed": streamed,
        "cache_dir": tmp_path / "cache",
        "cd_file": cd_file,
        "analyses": analyses,
        "tmp_path": tmp_path,
    }


OFF = Normalization(enabled=False, target=-14)
ON = Normalization(enabled=True, target=-14)


async def _client(env: dict[str, Any], norm: Normalization = OFF) -> tuple[TestClient, Any]:
    mass = env["mass"]
    cache = TranscodeCache(str(env["cache_dir"]), 10**9)
    await cache.setup()
    plugin = SimpleNamespace(
        mass=mass,
        logger=logging.getLogger("test.maa"),
        transcode_cache=cache,
        configured_format=FormatSpec("opus", 192),
        normalization=norm,
    )
    unregister = routes.MaaApi(plugin).register()  # type: ignore[arg-type]
    assert len(unregister) == 4
    app = web.Application()
    app["mass"] = mass
    app.router.add_route("*", "/{tail:.*}", mass.webserver.catch_all)
    client = TestClient(TestServer(app))
    await client.start_server()
    return client, plugin


AUTH = {"Authorization": f"Bearer {TOKEN}"}


def run(coro: Any) -> Any:
    return asyncio.run(coro)


def test_auth(env: dict[str, Any]) -> None:
    async def go() -> None:
        client, _ = await _client(env)
        try:
            resp = await client.get("/maa/info")
            assert resp.status == 401
            assert resp.headers["WWW-Authenticate"] == 'Bearer realm="Music Assistant"'
            assert (await resp.json())["error"] == "unauthorized"
            resp = await client.get("/maa/info", headers={"Authorization": "Bearer wrong"})
            assert resp.status == 401
            resp = await client.get(
                "/maa/track?provider=library&item_id=1",
                headers={"Authorization": f"Bearer {WEAK_TOKEN}"},
            )
            assert resp.status == 403
            assert (await resp.json())["error"] == "forbidden"
        finally:
            await client.close()

    run(go())


def test_info(env: dict[str, Any]) -> None:
    async def go() -> None:
        client, _ = await _client(env)
        try:
            resp = await client.get("/maa/info", headers=AUTH)
            assert resp.status == 200
            assert await resp.json() == {
                "plugin": "maa",
                "version": "0.2.0",
                "api": 1,
                "server_id": "server-123",
                "format": "opus-192",
                "formats": ["opus-<64..320>", "flac-16-44"],
                "variant": "off",
                "normalization": {"enabled": False, "target": -14},
                "cache": {"files": 0, "bytes": 0, "max_bytes": 10**9},
            }
        finally:
            await client.close()

    run(go())


@pytest.mark.parametrize(
    ("query", "status", "code"),
    [
        ("", 400, "missing_param"),
        ("?provider=library", 400, "missing_param"),
        ("?item_id=1", 400, "missing_param"),
        ("?provider=library&item_id=1&format=mp3", 400, "bad_format"),
        ("?provider=library&item_id=1&format=opus-500", 400, "bad_format"),
        ("?provider=library&item_id=999", 404, "not_found"),
        ("?provider=library&item_id=abc", 404, "not_found"),
        ("?provider=nosuchprov&item_id=x", 404, "not_found"),
        ("?provider=filesystem_local--x&item_id=missing.flac", 404, "not_found"),
        ("?provider=gone&item_id=x", 503, "provider_unavailable"),
        ("?provider=library&item_id=3", 503, "provider_unavailable"),
    ],
)
def test_track_errors(env: dict[str, Any], query: str, status: int, code: str) -> None:
    async def go() -> None:
        client, _ = await _client(env)
        try:
            resp = await client.get(f"/maa/track{query}", headers=AUTH)
            assert resp.status == status
            body = await resp.json()
            assert body["error"] == code
            assert isinstance(body["message"], str)
            if status == 503:
                assert resp.headers["Retry-After"] == "30"
        finally:
            await client.close()

    run(go())


def test_track_transcode_then_cached(env: dict[str, Any]) -> None:
    async def go() -> None:
        client, plugin = await _client(env)
        prov = env["prov"]
        key = cache_key(prov.instance_id, "hires.flac", "opus-192", "1700000000")
        try:
            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.status == 200
            assert await resp.read() == b"ENC:pcm1pcm2"
            assert resp.headers["X-MAA-Source"] == "transcoded"
            assert resp.headers["X-MAA-Format"] == "opus-192"
            assert resp.headers["Content-Type"] == "audio/ogg"
            assert resp.headers["ETag"] == f'"{key}"'
            assert resp.headers["Accept-Ranges"] == "bytes"
            assert resp.headers["Cache-Control"] == "private, max-age=31536000, immutable"
            assert resp.headers["X-MAA-Gain"] == "0.00"
            # the PCM request mirrors the source format
            _, pcm = env["streamed"][0]
            assert (pcm.content_type, pcm.sample_rate, pcm.bit_depth, pcm.channels) == (
                ContentType.PCM_S24LE,
                96000,
                24,
                2,
            )
            calls = prov.stream_details_calls

            # hit: served from disk without resolving stream details again
            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.status == 200
            assert resp.headers["X-MAA-Source"] == "cached"
            assert await resp.read() == b"ENC:pcm1pcm2"
            assert prov.stream_details_calls == calls
            assert len(env["streamed"]) == 1

            # the same track addressed by its provider item id shares the cache entry
            resp = await client.get(
                "/maa/track?provider=filesystem_local--x&item_id=hires.flac", headers=AUTH
            )
            assert resp.headers["X-MAA-Source"] == "cached"

            resp = await client.head("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.status == 200
            assert resp.headers["Content-Length"] == str(len(b"ENC:pcm1pcm2"))
            assert resp.headers["ETag"] == f'"{key}"'

            resp = await client.get(
                "/maa/track?provider=library&item_id=1", headers={**AUTH, "Range": "bytes=4-7"}
            )
            assert resp.status == 206
            assert resp.headers["Content-Range"] == "bytes 4-7/12"
            assert await resp.read() == b"pcm1"

            resp = await client.get(
                "/maa/track?provider=library&item_id=1",
                headers={**AUTH, "If-None-Match": f'"{key}"'},
            )
            assert resp.status == 304

            resp = await client.get("/maa/info", headers=AUTH)
            assert (await resp.json())["cache"]["files"] == 1
        finally:
            await client.close()
            await plugin.transcode_cache.close()

    run(go())


def test_track_flac_passthrough(env: dict[str, Any]) -> None:
    async def go() -> None:
        client, _ = await _client(env)
        try:
            resp = await client.get(
                "/maa/track?provider=library&item_id=2&format=flac-16-44", headers=AUTH
            )
            assert resp.status == 200
            assert resp.headers["X-MAA-Source"] == "passthrough"
            assert resp.headers["Content-Type"] == "audio/flac"
            assert resp.headers["ETag"].startswith('"pt-20-')
            assert await resp.read() == env["cd_file"].read_bytes()
            assert env["streamed"] == []
            # opus for the same file is transcoded
            resp = await client.get("/maa/track?provider=library&item_id=2", headers=AUTH)
            assert resp.headers["X-MAA-Source"] == "transcoded"
        finally:
            await client.close()

    run(go())


def test_track_transcode_pending_and_failed(
    env: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    async def go() -> None:
        client, plugin = await _client(env)
        try:
            monkeypatch.setattr(routes, "TRANSCODE_WAIT", 0.2)
            monkeypatch.setenv("FAKE_FFMPEG_DELAY", "1")
            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.status == 503
            assert resp.headers["Retry-After"] == "5"
            assert (await resp.json())["error"] == "transcode_pending"
            # the job kept running after the request gave up
            jobs = list(plugin.transcode_cache._jobs.values())
            assert len(jobs) == 1
            await asyncio.wait_for(asyncio.shield(jobs[0]), 30)
            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.headers["X-MAA-Source"] == "cached"

            monkeypatch.delenv("FAKE_FFMPEG_DELAY")
            monkeypatch.setattr(routes, "TRANSCODE_WAIT", 60)
            monkeypatch.setenv("FAKE_FFMPEG_FAIL", "1")
            resp = await client.get(
                "/maa/track?provider=library&item_id=1&format=opus-96", headers=AUTH
            )
            assert resp.status == 502
            body = await resp.json()
            assert body["error"] == "transcode_failed"
            assert "fake failure" in body["message"]
        finally:
            await client.close()
            await plugin.transcode_cache.close()

    run(go())


def test_prepare(env: dict[str, Any]) -> None:
    async def go() -> None:
        client, plugin = await _client(env)
        try:
            resp = await client.post(
                "/maa/prepare",
                headers=AUTH,
                json={
                    "format": "opus-128",
                    "tracks": [
                        {"provider": "library", "item_id": "1"},
                        {"provider": "library", "item_id": "999"},
                    ],
                },
            )
            assert resp.status == 202
            assert await resp.json() == {"queued": 1, "ready": 0}
            jobs = list(plugin.transcode_cache._jobs.values())
            await asyncio.wait_for(asyncio.gather(*jobs), 30)
            resp = await client.post(
                "/maa/prepare",
                headers=AUTH,
                json={
                    "format": "opus-128",
                    "tracks": [
                        {"provider": "library", "item_id": "1"},
                        {"provider": "library", "item_id": 2},
                    ],
                },
            )
            assert await resp.json() == {"queued": 1, "ready": 1}
            resp = await client.post(
                "/maa/prepare",
                headers=AUTH,
                json={"tracks": [{"provider": "library", "item_id": "2"}], "format": "flac-16-44"},
            )
            assert await resp.json() == {"queued": 0, "ready": 1}

            too_many = [{"provider": "library", "item_id": "1"}] * 11
            resp = await client.post("/maa/prepare", headers=AUTH, json={"tracks": too_many})
            assert resp.status == 400
            resp = await client.post("/maa/prepare", headers=AUTH, json={"tracks": "x"})
            assert resp.status == 400
            resp = await client.post("/maa/prepare", headers=AUTH, data=b"not json")
            assert (await resp.json())["error"] == "bad_request"
            resp = await client.post(
                "/maa/prepare", headers=AUTH, json={"format": "wav", "tracks": []}
            )
            assert (await resp.json())["error"] == "bad_format"
            resp = await client.post("/maa/prepare", json={"tracks": []})
            assert resp.status == 401
        finally:
            await client.close()
            await plugin.transcode_cache.close()

    run(go())


def test_normalized_track_two_stage(env: dict[str, Any], monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FAKE_LUFS", "-8.0")
    monkeypatch.setenv("FAKE_TP", "0.5")

    async def go() -> None:
        client, plugin = await _client(env, ON)
        prov = env["prov"]
        try:
            resp = await client.get("/maa/info", headers=AUTH)
            info = await resp.json()
            assert info["variant"] == "n-14"
            assert info["normalization"] == {"enabled": True, "target": -14}

            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.status == 200
            assert resp.headers["X-MAA-Source"] == "transcoded"
            assert resp.headers["X-MAA-Gain"] == "-6.00"
            key = cache_key(prov.instance_id, "hires.flac", "opus-192", "1700000000", "n-14")
            assert resp.headers["ETag"] == f'"{key}"'
            assert len(ffmpeg_calls(env["tmp_path"])) == 2  # measure + encode

            # the hit reads the gain from the sidecar
            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.headers["X-MAA-Source"] == "cached"
            assert resp.headers["X-MAA-Gain"] == "-6.00"
            resp = await client.head("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.headers["X-MAA-Gain"] == "-6.00"
        finally:
            await client.close()
            await plugin.transcode_cache.close()

    run(go())


def test_normalized_uses_ma_analysis_and_disables_passthrough(env: dict[str, Any]) -> None:
    env["analyses"]["cd.flac"] = SimpleNamespace(loudness_integrated=-20.0, true_peak=None)

    async def go() -> None:
        client, plugin = await _client(env, ON)
        try:
            resp = await client.get(
                "/maa/track?provider=library&item_id=2&format=flac-16-44", headers=AUTH
            )
            assert resp.status == 200
            # CD-quality FLAC is not passed through when normalising
            assert resp.headers["X-MAA-Source"] == "transcoded"
            assert resp.headers["X-MAA-Gain"] == "6.00"
            (call,) = ffmpeg_calls(env["tmp_path"])  # known loudness: one stage
            af = call[call.index("-af") + 1]
            # unknown true peak: a full-scale master is assumed, so +6 dB needs the limiter
            assert af.startswith(f"volume=6.00dB,{limiter_filters(FormatSpec('flac'))},aresample")
        finally:
            await client.close()
            await plugin.transcode_cache.close()

    run(go())


def test_provider_loudness_wins(env: dict[str, Any]) -> None:
    env["analyses"]["hires.flac"] = SimpleNamespace(loudness_integrated=-20.0, true_peak=-3.0)
    original = env["prov"]._sd_factory

    def with_loudness(item_id: str) -> StreamDetails:
        sd = original(item_id)
        sd.loudness = -11.5
        return sd

    env["prov"]._sd_factory = with_loudness

    async def go() -> None:
        client, plugin = await _client(env, ON)
        try:
            resp = await client.get("/maa/track?provider=library&item_id=1", headers=AUTH)
            assert resp.headers["X-MAA-Gain"] == "-2.50"
        finally:
            await client.close()
            await plugin.transcode_cache.close()

    run(go())
