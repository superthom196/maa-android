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

from maa.formats import FormatSpec
from maa.transcode import TranscodeCache, TranscodeError

OPUS = FormatSpec("opus", 192)
PCM = SimpleNamespace(content_type="s16le", sample_rate=44100, channels=2)
SD = SimpleNamespace(provider="prov", item_id="item")


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
        assert os.listdir(tmp_path / "c") == []

    asyncio.run(run())


def test_source_failure_raises_and_cleans_up(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        with pytest.raises(TranscodeError, match="source broke"):
            await cache.get_or_create(
                "e" * 32, OPUS, SD, PCM, make_factory([b"abc"], [], fail=True)
            )
        assert os.listdir(tmp_path / "c") == []

    asyncio.run(run())


def test_empty_source_is_an_error(tmp_path: Path, fake_ffmpeg: Path) -> None:
    async def run() -> None:
        cache = TranscodeCache(str(tmp_path / "c"), 10**9)
        await cache.setup()
        with pytest.raises(TranscodeError, match="no audio"):
            await cache.get_or_create("f" * 32, OPUS, SD, PCM, make_factory([], []))
        assert os.listdir(tmp_path / "c") == []

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
    assert sorted(os.listdir(cache_dir)) == [f"{'2' * 32}.opus"]


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
        assert sorted(os.listdir(cache_dir)) == sorted([names[1], names[2], "notes.txt"])
        # serving bumps the LRU position (atime) but keeps mtime (Last-Modified)
        before = os.stat(cache_dir / names[1]).st_mtime
        assert await cache.lookup("b" * 32, OPUS) is not None
        assert os.stat(cache_dir / names[1]).st_mtime == before
        cache.max_bytes = 150
        assert await cache.trim() == 1
        assert sorted(os.listdir(cache_dir)) == sorted([names[1], "notes.txt"])
        assert await cache.clear() == (1, 100)
        assert os.listdir(cache_dir) == ["notes.txt"]
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
        assert os.listdir(tmp_path / "c") == []
        with pytest.raises(TranscodeError):
            cache.ensure_job("8" * 32, OPUS, SD, PCM, make_factory([b"abc"], []))

    asyncio.run(run())
