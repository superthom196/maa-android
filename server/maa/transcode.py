"""
On-disk cache of transcoded tracks for the MAA plugin.

Each cached track is a complete file named ``<key>.<ext>``, with a small ``<key>.json``
sidecar holding the normalisation that was applied (gain, loudness, limiter). A transcode
writes to ``<key>.part`` and is only renamed into place when ffmpeg exited cleanly, so a
cached file is always complete (with finalized FLAC STREAMINFO / Ogg pages) and can be served
with ranges.

Volume normalisation (see GainPlan): when the loudness is already known the PCM is encoded in
one pass with the gain applied in float before resampling. Otherwise the job runs two stages:
stage 1 writes the PCM to a float intermediate (``<key>.measure.wav``) while ebur128 measures
integrated loudness and true peak in the same pass; stage 2 encodes the intermediate with the
gain (and, only when needed, a limiter). Readings are stored per source in ``loudness/`` so
other formats of the same track skip the measurement.

Jobs run as independent asyncio tasks (at most ``concurrency`` jobs at once) and are
de-duplicated by key, so a client that disconnects or times out never aborts a job and
several requests for the same track share one transcode.

LRU: serving a file bumps its access time (the modification time is left alone so that
Last-Modified stays stable for the client); trimming removes the least recently used files
until the cache fits its size limit again.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import shutil
import time
from collections import deque
from contextlib import aclosing, suppress
from typing import TYPE_CHECKING, Any

from .formats import (
    FormatSpec,
    GainPlan,
    compute_gain,
    encode_file_args,
    ffmpeg_args,
    measure_args,
    needs_limiter,
    parse_ebur128,
)

if TYPE_CHECKING:
    from collections.abc import AsyncGenerator, Callable

    # (streamdetails, pcm_format) -> async generator of raw PCM chunks
    StreamFactory = Callable[[Any, Any], AsyncGenerator[bytes]]

# finished cache files; anything else in the directory is never served or counted
CACHE_FILE_RE = re.compile(r"^([0-9a-f]{32})\.(opus|flac)$")
META_FILE_RE = re.compile(r"^([0-9a-f]{32})\.json$")
PART_SUFFIX = ".part"
MEASURE_SUFFIX = ".measure.wav"
LOUDNESS_SUBDIR = "loudness"
# a job that has not finished after this long is considered stuck (source stalled)
JOB_TIMEOUT = 30 * 60
# renice the encoder so it never competes with live playback on a small host
ENCODER_NICENESS = 10
STDERR_TAIL_LINES = 20
# stage 1 logs at info level: keep enough lines for the ebur128 summary
MEASURE_STDERR_LINES = 400

LOGGER = logging.getLogger("music_assistant.providers.maa.transcode")


class TranscodeError(Exception):
    """Raised when a transcode job failed."""


class TranscodeCache:
    """Disk cache with de-duplicated, concurrency-limited transcode jobs."""

    def __init__(
        self,
        cache_dir: str,
        max_bytes: int,
        concurrency: int = 2,
        logger: logging.Logger | None = None,
    ) -> None:
        """Initialize the cache (call ``setup`` before use)."""
        self.dir = cache_dir
        self.loudness_dir = os.path.join(cache_dir, LOUDNESS_SUBDIR)
        self.max_bytes = max_bytes
        self.logger = logger or LOGGER
        self._jobs: dict[str, asyncio.Task[str]] = {}
        self._sem = asyncio.Semaphore(concurrency)
        self._trim_lock = asyncio.Lock()
        self._closing = False

    # ------------------------------------------------------------------ setup/teardown

    async def setup(self) -> None:
        """Create the cache directory and remove leftovers of a crash/restart."""

        def _setup() -> int:
            os.makedirs(self.loudness_dir, exist_ok=True)
            removed = 0
            with os.scandir(self.dir) as it:
                entries = list(it)
            finished = {m[1] for e in entries if (m := CACHE_FILE_RE.match(e.name))}
            for entry in entries:
                stale = entry.name.endswith((PART_SUFFIX, MEASURE_SUFFIX)) or (
                    (m := META_FILE_RE.match(entry.name)) is not None and m[1] not in finished
                )
                if stale and entry.is_file(follow_symlinks=False):
                    with suppress(OSError):
                        os.unlink(entry.path)
                        removed += 1
            return removed

        if removed := await asyncio.to_thread(_setup):
            self.logger.debug("Removed %s stale file(s) from %s", removed, self.dir)
        # enforce the (possibly lowered) size limit right away
        await self.trim()

    async def close(self) -> None:
        """Cancel all running jobs (their partial files are removed)."""
        self._closing = True
        jobs = list(self._jobs.values())
        for task in jobs:
            task.cancel()
        if jobs:
            await asyncio.gather(*jobs, return_exceptions=True)
        self._jobs.clear()

    # ------------------------------------------------------------------ lookups

    def final_path(self, key: str, spec: FormatSpec) -> str:
        """Return the path of the finished cache file for key/spec."""
        return os.path.join(self.dir, f"{key}.{spec.ext}")

    def meta_path(self, key: str) -> str:
        """Return the path of the metadata sidecar of a cache file."""
        return os.path.join(self.dir, f"{key}.json")

    async def lookup(self, key: str, spec: FormatSpec) -> str | None:
        """Return the cached file for key/spec (bumping it in the LRU), or None."""
        path = self.final_path(key, spec)

        def _lookup() -> bool:
            try:
                st = os.stat(path)
            except OSError:
                return False
            # bump atime only: mtime (Last-Modified) must stay stable for If-Range
            with suppress(OSError):
                os.utime(path, (time.time(), st.st_mtime))
            return True

        return path if await asyncio.to_thread(_lookup) else None

    async def read_meta(self, key: str) -> dict[str, Any] | None:
        """Return the metadata sidecar of a cache file (gain applied etc.), if any."""
        return await asyncio.to_thread(_read_json, self.meta_path(key))

    def pending(self, key: str) -> asyncio.Task[str] | None:
        """Return the in-flight job for key, if any."""
        task = self._jobs.get(key)
        return task if task is not None and not task.done() else None

    async def stored_loudness(self, source_key: str) -> tuple[float | None, float | None]:
        """Return a loudness reading measured earlier for a source: (LUFS, true peak)."""
        data = await asyncio.to_thread(
            _read_json, os.path.join(self.loudness_dir, f"{source_key}.json")
        )
        if not data or not isinstance(data.get("loudness"), (int, float)):
            return None, None
        true_peak = data.get("true_peak")
        return float(data["loudness"]), (
            float(true_peak) if isinstance(true_peak, (int, float)) else None
        )

    # ------------------------------------------------------------------ jobs

    def ensure_job(
        self,
        key: str,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
        plan: GainPlan | None = None,
    ) -> asyncio.Task[str]:
        """
        Return the job producing key/spec, starting it when none is running.

        The job is an independent task: cancelling or abandoning the caller never stops it.
        Await it through ``asyncio.shield``.
        """
        if self._closing:
            raise TranscodeError("transcode cache is shutting down")
        if (task := self.pending(key)) is not None:
            return task
        task = asyncio.create_task(
            self._run_job(
                key, spec, streamdetails, pcm_format, stream_factory, plan or GainPlan()
            ),
            name=f"maa_transcode_{key}",
        )
        self._jobs[key] = task

        def _done(finished: asyncio.Task[str]) -> None:
            if self._jobs.get(key) is finished:
                self._jobs.pop(key, None)
            if finished.cancelled():
                return
            if (err := finished.exception()) is not None:
                # retrieved here so background (prepare) jobs never go unobserved
                self.logger.warning("Transcode %s (%s) failed: %s", key, spec.name, err)

        task.add_done_callback(_done)
        return task

    async def get_or_create(
        self,
        key: str,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
        plan: GainPlan | None = None,
    ) -> str:
        """
        Return the path of the finished file for key/spec, transcoding it when needed.

        The transcode itself is shielded: cancelling this call (client went away, timeout)
        leaves the job running so the file is ready for the next request.
        """
        if (path := await self.lookup(key, spec)) is not None:
            return path
        task = self.ensure_job(key, spec, streamdetails, pcm_format, stream_factory, plan)
        return await asyncio.shield(task)

    async def _run_job(
        self,
        key: str,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
        plan: GainPlan,
    ) -> str:
        final = self.final_path(key, spec)
        part = os.path.join(self.dir, f"{key}{PART_SUFFIX}")
        intermediate = os.path.join(self.dir, f"{key}{MEASURE_SUFFIX}")
        async with self._sem:
            # another job (or an earlier request) may have finished it meanwhile
            if await asyncio.to_thread(os.path.isfile, final):
                return final
            started = time.monotonic()
            try:
                try:
                    async with asyncio.timeout(JOB_TIMEOUT):
                        meta = await self._transcode(
                            spec,
                            streamdetails,
                            pcm_format,
                            stream_factory,
                            plan,
                            part,
                            intermediate,
                        )
                except TimeoutError as err:
                    msg = f"transcode did not finish within {JOB_TIMEOUT}s"
                    raise TranscodeError(msg) from err
                await asyncio.to_thread(self._finalize, key, part, final, meta)
            except BaseException:
                for leftover in (part, self.meta_path(key)):
                    with suppress(OSError):
                        await asyncio.to_thread(os.unlink, leftover)
                raise
            finally:
                with suppress(OSError):
                    await asyncio.to_thread(os.unlink, intermediate)
            self.logger.debug(
                "Transcoded %s/%s to %s in %.1fs (%s)",
                getattr(streamdetails, "provider", "?"),
                getattr(streamdetails, "item_id", "?"),
                spec.name,
                time.monotonic() - started,
                meta,
            )
        # housekeeping outside the semaphore; a failing trim must not fail the job
        try:
            await self.trim()
        except Exception as err:
            self.logger.warning("Trimming the MAA cache failed: %s", err)
        return final

    def _finalize(self, key: str, part: str, final: str, meta: dict[str, Any]) -> None:
        """Write the sidecar, then move the finished file into place (blocking)."""
        _write_json(self.meta_path(key), meta)
        os.replace(part, final)

    async def _transcode(
        self,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
        plan: GainPlan,
        part: str,
        intermediate: str,
    ) -> dict[str, Any]:
        """Produce ``part`` according to the plan; return the sidecar metadata."""
        content_type = pcm_format.content_type
        pcm_codec = str(getattr(content_type, "value", content_type))
        sample_rate = int(pcm_format.sample_rate)
        channels = int(pcm_format.channels)

        def _source() -> AsyncGenerator[bytes]:
            return stream_factory(streamdetails, pcm_format)

        loudness, true_peak, loudness_source = plan.loudness, plan.true_peak, "none"
        if plan.normalize:
            loudness_source = plan.loudness_source if loudness is not None else "none"
            if loudness is None and plan.source_key:
                loudness, true_peak = await self.stored_loudness(plan.source_key)
                if loudness is not None:
                    loudness_source = "stored"
        two_stage = plan.normalize and loudness is None

        if two_stage:
            # stage 1: PCM -> float intermediate, measuring loudness in the same pass
            log = await self._run_ffmpeg(
                measure_args(pcm_codec, sample_rate, channels, intermediate),
                _source,
                MEASURE_STDERR_LINES,
            )
            loudness, true_peak = parse_ebur128(log)
            if loudness is None:
                self.logger.warning(
                    "No loudness reading for %s, encoding without gain",
                    getattr(streamdetails, "item_id", "?"),
                )
            else:
                loudness_source = "measured"
                if plan.source_key:
                    await asyncio.to_thread(
                        _write_json,
                        os.path.join(self.loudness_dir, f"{plan.source_key}.json"),
                        {"loudness": loudness, "true_peak": true_peak, "measured_at": time.time()},
                    )

        gain = None
        if plan.normalize and plan.target is not None:
            gain = compute_gain(plan.target, loudness)
        limiter = needs_limiter(spec, gain, true_peak)

        if two_stage:
            # stage 2: encode the intermediate with the gain applied
            await self._run_ffmpeg(
                encode_file_args(spec, intermediate, channels, part, gain, limiter), None
            )
        else:
            await self._run_ffmpeg(
                ffmpeg_args(spec, pcm_codec, sample_rate, channels, part, gain, limiter), _source
            )
        if not await asyncio.to_thread(_file_size, part):
            msg = "ffmpeg produced no output"
            raise TranscodeError(msg)
        return {
            "format": spec.name,
            "normalized": plan.normalize,
            "target": plan.target if plan.normalize else None,
            "gain_db": gain if gain is not None else 0.0,
            "loudness": loudness,
            "true_peak": true_peak,
            "loudness_source": loudness_source,
            "limiter": limiter,
        }

    async def _run_ffmpeg(
        self,
        args: list[str],
        source: Callable[[], AsyncGenerator[bytes]] | None,
        stderr_lines: int = STDERR_TAIL_LINES,
    ) -> str:
        """
        Run one ffmpeg process, feeding stdin from ``source`` when given.

        Returns the (tail of the) stderr output; raises TranscodeError when the source or
        ffmpeg failed or the source delivered nothing.
        """
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdin=asyncio.subprocess.PIPE if source is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        with suppress(OSError, AttributeError):
            os.setpriority(os.PRIO_PROCESS, proc.pid, ENCODER_NICENESS)
        assert proc.stderr is not None
        stderr_tail: deque[str] = deque(maxlen=stderr_lines)

        async def _read_stderr() -> None:
            assert proc.stderr is not None
            while line := await proc.stderr.readline():
                stderr_tail.append(line.decode("utf-8", "replace").rstrip())

        stderr_task = asyncio.create_task(_read_stderr())
        source_error: BaseException | None = None
        fed_bytes = 0
        try:
            if source is not None:
                assert proc.stdin is not None
                try:
                    async with aclosing(source()) as stream:
                        async for chunk in stream:
                            if not chunk:
                                continue
                            proc.stdin.write(chunk)
                            await proc.stdin.drain()
                            fed_bytes += len(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    # ffmpeg quit early; its exit code and stderr tell why
                    pass
                except asyncio.CancelledError:
                    raise
                except Exception as err:
                    source_error = err
                finally:
                    with suppress(Exception):
                        proc.stdin.close()
                    with suppress(Exception):
                        await proc.stdin.wait_closed()
            if source_error is not None and proc.returncode is None:
                # do not finalize a truncated file
                proc.kill()
            returncode = await proc.wait()
            with suppress(Exception):
                await asyncio.wait_for(stderr_task, 5)
        finally:
            if proc.returncode is None:
                with suppress(ProcessLookupError):
                    proc.kill()
                with suppress(Exception):
                    await proc.wait()
            if not stderr_task.done():
                stderr_task.cancel()

        tail = " | ".join(list(stderr_tail)[-STDERR_TAIL_LINES:])
        if source_error is not None:
            msg = f"reading the source failed: {source_error!r}"
            raise TranscodeError(msg) from source_error
        if returncode != 0:
            msg = f"ffmpeg exited with code {returncode}: {tail or 'no output'}"
            raise TranscodeError(msg)
        if source is not None and fed_bytes == 0:
            msg = "the source delivered no audio"
            raise TranscodeError(msg)
        return "\n".join(stderr_tail)

    # ------------------------------------------------------------------ housekeeping

    def _scan(self) -> list[tuple[float, int, str, str]]:
        """Return (last_used, size, path, key) for every finished cache file."""
        result: list[tuple[float, int, str, str]] = []
        try:
            entries = list(os.scandir(self.dir))
        except FileNotFoundError:
            return result
        for entry in entries:
            if not (match := CACHE_FILE_RE.match(entry.name)):
                continue
            with suppress(OSError):
                if not entry.is_file(follow_symlinks=False):
                    continue
                st = entry.stat(follow_symlinks=False)
                result.append((max(st.st_atime, st.st_mtime), st.st_size, entry.path, match[1]))
        return result

    def _delete(self, path: str, key: str) -> bool:
        """Delete a cache file and its sidecar (blocking)."""
        try:
            os.unlink(path)
        except OSError:
            return False
        with suppress(OSError):
            os.unlink(self.meta_path(key))
        return True

    async def trim(self) -> int:
        """Delete least recently used files until the cache fits max_bytes; return count."""
        async with self._trim_lock:
            in_flight = set(self._jobs)
            max_bytes = self.max_bytes

            def _trim() -> int:
                files = self._scan()
                total = sum(size for _, size, _, _ in files)
                if total <= max_bytes:
                    return 0
                removed = 0
                for _, size, path, key in sorted(files):
                    if key in in_flight:
                        continue
                    if not self._delete(path, key):
                        continue
                    removed += 1
                    total -= size
                    if total <= max_bytes:
                        break
                return removed

            removed = await asyncio.to_thread(_trim)
        if removed:
            self.logger.debug("Trimmed %s file(s) from the MAA cache", removed)
        return removed

    async def stats(self) -> dict[str, int]:
        """Return the number of cached files and their total size in bytes."""
        files = await asyncio.to_thread(self._scan)
        return {"files": len(files), "bytes": sum(size for _, size, _, _ in files)}

    async def clear(self) -> tuple[int, int]:
        """Delete every finished cache file and stored loudness reading (not in-flight jobs)."""
        in_flight = set(self._jobs)

        def _clear() -> tuple[int, int]:
            count = 0
            freed = 0
            for _, size, path, key in self._scan():
                if key in in_flight:
                    continue
                if self._delete(path, key):
                    count += 1
                    freed += size
            with suppress(OSError), os.scandir(self.loudness_dir) as entries:
                for entry in entries:
                    if entry.name.endswith(".json"):
                        with suppress(OSError):
                            os.unlink(entry.path)
            return count, freed

        return await asyncio.to_thread(_clear)

    async def remove_all(self) -> None:
        """Remove the whole cache directory (used when the plugin is removed)."""
        await asyncio.to_thread(shutil.rmtree, self.dir, True)


def _file_size(path: str) -> int:
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def _read_json(path: str) -> dict[str, Any] | None:
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def _write_json(path: str, data: dict[str, Any]) -> None:
    """Write a small JSON file atomically."""
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh)
    os.replace(tmp, path)
