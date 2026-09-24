"""
Tests for maa.transcode.TranscodeCache using a fake ffmpeg on PATH.

The fake copies stdin to the output file (the last argument), so these tests cover the job
plumbing (dedupe, .part handling, failure cleanup, LRU trim) without needing a real ffmpeg.
The real encoder command line is covered by test_formats.
"""

from __future__ import annotations

import asyncio
import os
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from conftest import ffmpeg_calls
from maa.formats import FormatSpec, GainPlan, limiter_filters, source_key
from maa.transcode import TranscodeCache, TranscodeError

OPUS = FormatSpec("opus", 192)
FLAC = FormatSpec("flac")
PCM = SimpleNamespace(content_type="s16le", sample_rate=44100, channels=2)
SD = SimpleNamespace(provider="prov", item_id="item")


def _files(path: Path) -> list[str]:
    """List a cache directory without the loudness/ subdirectory."""
    return sorted(name for name in os.listdir(path) if name != "loudness")


def make_factory(chunks: list[bytes], calls: list[int], fail: bool = False):  # noqa: ANN201
    def factory(streamdetails: object, pcm_format: object):  # noqa: ANN202
        calls.append(1)

        async def gen():  # noqa: ANN202
            for chunk in chunks:
                await asyncio.sleep(0)
                yield chunk
            if fail:
                raise RuntimeError("source broke")

        return gen()

    return factory


def test_transcode_writes_final_file(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "cache"), 10**9)
        await cache.setup()
        calls: list[int] = []
        path = await cache.get_or_create("a" * 32, OPUS, SD, PCM, make_factory([b"x" * 10], calls))
        assert path == str(tmp_path / "cache" / f"{'a' * 32}.opus")
        assert Path(path).read_bytes() == b"ENC:" + b"x" * 10
        assert not list((tmp_path / "cache").glob("*.part"))
        # second call is a cache hit, the source is not opened again
        assert await cache.get_or_create("a" * 32, OPUS, SD, PCM, make_factory([], calls)) == path
        assert len(calls) == 1
        assert await cache.stats() == {"files": 1, "bytes": 14}

    asyncio.run(run())


def test_concurrent_requests_share_one_job(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        calls: list[int] = []
        factory = make_factory([b"abc"], calls)
        results = await asyncio.gather(
            *(cache.get_or_create("b" * 32, OPUS, SD, PCM, factory) for _ in range(5))
        )
        assert len(set(results)) == 1
        assert len(calls) == 1

    asyncio.run(run())


def test_job_survives_caller_cancellation(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_FFMPEG_DELAY", "0.5")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        calls: list[int] = []
        key = "c" * 32
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(
                cache.get_or_create(key, OPUS, SD, PCM, make_factory([b"abc"], calls)), 0.1
            )
        task = cache.pending(key)
        assert task is not None
        path = await asyncio.shield(task)
        assert Path(path).is_file()
        assert cache.pending(key) is None

    asyncio.run(run())


def test_ffmpeg_failure_raises_and_cleans_up(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_FFMPEG_FAIL", "1")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        with pytest.raises(TranscodeError, match="code 3.*fake failure line 2"):
            await cache.get_or_create("d" * 32, OPUS, SD, PCM, make_factory([b"abc"], []))
        assert _files(tmp_path / "c") == []

    asyncio.run(run())


def test_source_failure_raises_and_cleans_up(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        with pytest.raises(TranscodeError, match="source broke"):
            await cache.get_or_create(
                "e" * 32, OPUS, SD, PCM, make_factory([b"abc"], [], fail=True)
            )
        assert _files(tmp_path / "c") == []

    asyncio.run(run())


def test_empty_source_is_an_error(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        with pytest.raises(TranscodeError, match="no audio"):
            await cache.get_or_create("f" * 32, OPUS, SD, PCM, make_factory([], []))
        assert _files(tmp_path / "c") == []

    asyncio.run(run())


def test_setup_removes_stale_parts(tmp_path: Path) -> None:
    cache_dir = tmp_path / "c"
    cache_dir.mkdir()
    (cache_dir / f"{'1' * 32}.part").write_bytes(b"partial")
    (cache_dir / f"{'2' * 32}.opus").write_bytes(b"done")

    async def run() -> None:
        cache = TranscodeCache(str(cache_dir), 10**9)
        await cache.setup()

    asyncio.run(run())
    assert _files(cache_dir) == [f"{'2' * 32}.opus"]


def test_trim_removes_least_recently_used(tmp_path: Path) -> None:
    cache_dir = tmp_path / "c"
    cache_dir.mkdir()
    now = time.time()
    names = [f"{c * 32}.opus" for c in "abc"]
    for age, name in zip((300, 200, 100), names, strict=True):
        path = cache_dir / name
        path.write_bytes(b"x" * 100)
        os.utime(path, (now - age, now - age))
    unrelated = cache_dir / "notes.txt"
    unrelated.write_bytes(b"y" * 1000)

    async def run() -> None:
        cache = TranscodeCache(str(cache_dir), 250)
        await cache.setup()  # trims: 300 bytes > 250
        assert _files(cache_dir) == sorted([names[1], names[2], "notes.txt"])
        # serving bumps the LRU position (atime) but keeps mtime (Last-Modified)
        before = os.stat(cache_dir / names[1]).st_mtime
        assert await cache.lookup("b" * 32, OPUS) is not None
        assert os.stat(cache_dir / names[1]).st_mtime == before
        cache.max_bytes = 150
        assert await cache.trim() == 1
        assert _files(cache_dir) == sorted([names[1], "notes.txt"])
        assert await cache.clear() == (1, 100)
        assert _files(cache_dir) == ["notes.txt"]
        assert await cache.lookup("b" * 32, OPUS) is None

    asyncio.run(run())


def test_close_cancels_running_jobs(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_FFMPEG_DELAY", "5")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        task = cache.ensure_job("9" * 32, OPUS, SD, PCM, make_factory([b"abc"], []))
        await asyncio.sleep(0.3)
        started = time.monotonic()
        await cache.close()
        assert time.monotonic() - started < 3
        assert task.cancelled()
        assert _files(tmp_path / "c") == []
        with pytest.raises(TranscodeError):
            cache.ensure_job("8" * 32, OPUS, SD, PCM, make_factory([b"abc"], []))

    asyncio.run(run())


# ---------------------------------------------------------------- normalisation


def _af(args: list[str]) -> str:
    return args[args.index("-af") + 1]


def test_normalization_off_is_plain_single_stage(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        await cache.get_or_create("1" * 32, OPUS, SD, PCM, make_factory([b"abc"], []), GainPlan())
        (call,) = ffmpeg_calls(tmp_path)
        assert _af(call) == "aresample=resampler=soxr:precision=28"
        meta = await cache.read_meta("1" * 32)
        assert meta is not None
        assert meta["normalized"] is False
        assert meta["gain_db"] == 0.0
        assert meta["limiter"] is False

    asyncio.run(run())


def test_two_stage_measure_then_encode(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_LUFS", "-8.0")
    monkeypatch.setenv("FAKE_TP", "0.5")
    src = source_key("prov", "item", "1")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        calls: list[int] = []
        plan = GainPlan(normalize=True, target=-14, source_key=src)
        path = await cache.get_or_create(
            "2" * 32, OPUS, SD, PCM, make_factory([b"pcm1", b"pcm2"], calls), plan
        )
        # the source is read once; stage 2 reads the intermediate
        assert len(calls) == 1
        assert Path(path).read_bytes() == b"ENC:pcm1pcm2"
        stage1, stage2 = ffmpeg_calls(tmp_path)
        assert "ebur128=peak=true:framelog=verbose" in stage1
        assert stage1[stage1.index("-i") + 1] == "-"
        intermediate = stage1[-1]
        assert intermediate.endswith(".measure.wav")
        assert stage2[stage2.index("-i") + 1] == intermediate
        # loud master: turned down 6 dB, no limiter needed (0.5 - 6 = -5.5 dBTP)
        assert _af(stage2) == "volume=-6.00dB,aresample=resampler=soxr:precision=28"
        assert not Path(intermediate).exists()
        meta = await cache.read_meta("2" * 32)
        assert meta == {
            "format": "opus-192",
            "normalized": True,
            "target": -14,
            "gain_db": -6.0,
            "loudness": -8.0,
            "true_peak": 0.5,
            "loudness_source": "measured",
            "limiter": False,
        }
        assert await cache.stored_loudness(src) == (-8.0, 0.5)

        # another format of the same source reuses the reading: one stage only
        await cache.get_or_create(
            "3" * 32, FLAC, SD, PCM, make_factory([b"pcm1"], calls), plan
        )
        third = ffmpeg_calls(tmp_path)[2]
        assert len(ffmpeg_calls(tmp_path)) == 3
        assert third[third.index("-i") + 1] == "-"
        assert _af(third).startswith("volume=-6.00dB,aresample")
        meta3 = await cache.read_meta("3" * 32)
        assert meta3 is not None
        assert meta3["loudness_source"] == "stored"

        # clear drops cache files, sidecars and stored readings
        assert (await cache.clear())[0] == 2
        assert await cache.read_meta("2" * 32) is None
        assert await cache.stored_loudness(src) == (None, None)

    asyncio.run(run())


def test_quiet_track_is_boosted_with_limiter(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_LUFS", "-24.0")
    monkeypatch.setenv("FAKE_TP", "-9.5")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        plan = GainPlan(normalize=True, target=-14)
        await cache.get_or_create("4" * 32, OPUS, SD, PCM, make_factory([b"x"], []), plan)
        stage2 = ffmpeg_calls(tmp_path)[1]
        # +10 dB puts the -9.5 dBTP peak at +0.5: the limiter guards it
        assert _af(stage2) == (
            f"volume=10.00dB,{limiter_filters(OPUS)},aresample=resampler=soxr:precision=28"
        )
        meta = await cache.read_meta("4" * 32)
        assert meta is not None
        assert (meta["gain_db"], meta["limiter"]) == (10.0, True)

    asyncio.run(run())


@pytest.mark.parametrize(
    ("loudness", "true_peak", "expected_af"),
    [
        (-10.0, None, "volume=-4.00dB,aresample=resampler=soxr:precision=28"),
        (-20.0, -12.0, "volume=6.00dB,aresample=resampler=soxr:precision=28"),
        (
            -20.0,
            None,
            f"volume=6.00dB,{limiter_filters(OPUS)},aresample=resampler=soxr:precision=28",
        ),
    ],
)
def test_known_loudness_is_single_stage(
    tmp_path: Path,
    fake_ffmpeg: Path,
    loudness: float,
    true_peak: float | None,
    expected_af: str,
) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        plan = GainPlan(
            normalize=True,
            target=-14,
            loudness=loudness,
            true_peak=true_peak,
            loudness_source="analysis",
        )
        await cache.get_or_create("5" * 32, OPUS, SD, PCM, make_factory([b"x"], []), plan)
        (call,) = ffmpeg_calls(tmp_path)
        assert _af(call) == expected_af
        meta = await cache.read_meta("5" * 32)
        assert meta is not None
        assert meta["loudness_source"] == "analysis"

    asyncio.run(run())


def test_unparseable_measurement_encodes_without_gain(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FAKE_LUFS", "-inf")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        plan = GainPlan(normalize=True, target=-14)
        await cache.get_or_create("6" * 32, OPUS, SD, PCM, make_factory([b"x"], []), plan)
        stage2 = ffmpeg_calls(tmp_path)[1]
        assert _af(stage2) == "aresample=resampler=soxr:precision=28"
        meta = await cache.read_meta("6" * 32)
        assert meta is not None
        assert (meta["gain_db"], meta["loudness_source"]) == (0.0, "none")

    asyncio.run(run())


def test_failed_stage_two_cleans_up(
    tmp_path: Path, fake_ffmpeg: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # fail only the encode: the fake fails every call, so check stage 1 failure cleanup too
    monkeypatch.setenv("FAKE_FFMPEG_FAIL", "1")

    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        plan = GainPlan(normalize=True, target=-14)
        with pytest.raises(TranscodeError):
            await cache.get_or_create("7" * 32, OPUS, SD, PCM, make_factory([b"x"], []), plan)
        assert _files(tmp_path / "c") == []

    asyncio.run(run())


def test_trim_and_setup_handle_sidecars(tmp_path: Path) -> None:
    cache_dir = tmp_path / "c"
    cache_dir.mkdir()
    (cache_dir / f"{'a' * 32}.opus").write_bytes(b"x" * 100)
    (cache_dir / f"{'a' * 32}.json").write_text('{"gain_db": -3.0}')
    (cache_dir / f"{'b' * 32}.json").write_text("{}")  # orphan
    (cache_dir / f"{'c' * 32}.measure.wav").write_bytes(b"stale")

    async def run() -> None:
        cache = TranscodeCache(str(cache_dir), 10**9)
        await cache.setup()
        assert _files(cache_dir) == sorted([f"{'a' * 32}.opus", f"{'a' * 32}.json"])
        assert await cache.read_meta("a" * 32) == {"gain_db": -3.0}
        cache.max_bytes = 10
        assert await cache.trim() == 1
        assert _files(cache_dir) == []

    asyncio.run(run())
