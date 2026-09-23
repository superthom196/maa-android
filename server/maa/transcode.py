"""
On-disk cache of transcoded tracks for the MAA plugin.

Each cached track is a complete file named ``<key>.<ext>``. A transcode writes to
``<key>.part`` and is only renamed into place when ffmpeg exited cleanly, so a cached file is
always complete (with finalized FLAC STREAMINFO / Ogg pages) and can be served with ranges.

Jobs run as independent asyncio tasks (at most ``concurrency`` ffmpeg encoders at once) and
are de-duplicated by key, so a client that disconnects or times out never aborts a job and
several requests for the same track share one transcode.

LRU: serving a file bumps its access time (the modification time is left alone so that
Last-Modified stays stable for the client); trimming removes the least recently used files
until the cache fits its size limit again.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import shutil
import time
from collections import deque
from contextlib import aclosing, suppress
from typing import TYPE_CHECKING, Any

from .formats import FormatSpec, ffmpeg_args

if TYPE_CHECKING:
    from collections.abc import AsyncGenerator, Callable

    # (streamdetails, pcm_format) -> async generator of raw PCM chunks
    StreamFactory = Callable[[Any, Any], AsyncGenerator[bytes]]

# finished cache files; anything else in the directory is never served or counted
CACHE_FILE_RE = re.compile(r"^([0-9a-f]{32})\.(opus|flac)$")
PART_SUFFIX = ".part"
# a job that has not finished after this long is considered stuck (source stalled)
JOB_TIMEOUT = 30 * 60
# renice the encoder so it never competes with live playback on a small host
ENCODER_NICENESS = 10
STDERR_TAIL_LINES = 20

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
        self.max_bytes = max_bytes
        self.logger = logger or LOGGER
        self._jobs: dict[str, asyncio.Task[str]] = {}
        self._sem = asyncio.Semaphore(concurrency)
        self._trim_lock = asyncio.Lock()
        self._closing = False

    # ------------------------------------------------------------------ setup/teardown

    async def setup(self) -> None:
        """Create the cache directory and remove partial files left by a crash/restart."""

        def _setup() -> int:
            os.makedirs(self.dir, exist_ok=True)
            removed = 0
            with os.scandir(self.dir) as entries:
                for entry in entries:
                    if entry.name.endswith(PART_SUFFIX) and entry.is_file(follow_symlinks=False):
                        with suppress(OSError):
                            os.unlink(entry.path)
                            removed += 1
            return removed

        if removed := await asyncio.to_thread(_setup):
            self.logger.debug("Removed %s stale partial file(s) from %s", removed, self.dir)
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

    def pending(self, key: str) -> asyncio.Task[str] | None:
        """Return the in-flight job for key, if any."""
        task = self._jobs.get(key)
        return task if task is not None and not task.done() else None

    # ------------------------------------------------------------------ jobs

    def ensure_job(
        self,
        key: str,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
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
            self._run_job(key, spec, streamdetails, pcm_format, stream_factory),
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
    ) -> str:
        """
        Return the path of the finished file for key/spec, transcoding it when needed.

        The transcode itself is shielded: cancelling this call (client went away, timeout)
        leaves the job running so the file is ready for the next request.
        """
        if (path := await self.lookup(key, spec)) is not None:
            return path
        task = self.ensure_job(key, spec, streamdetails, pcm_format, stream_factory)
        return await asyncio.shield(task)

    async def _run_job(
        self,
        key: str,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
    ) -> str:
        final = self.final_path(key, spec)
        part = os.path.join(self.dir, f"{key}{PART_SUFFIX}")
        async with self._sem:
            # another job (or an earlier request) may have finished it meanwhile
            if await asyncio.to_thread(os.path.isfile, final):
                return final
            started = time.monotonic()
            try:
                try:
                    async with asyncio.timeout(JOB_TIMEOUT):
                        await self._transcode(
                            spec, streamdetails, pcm_format, stream_factory, part
                        )
                except TimeoutError as err:
                    msg = f"transcode did not finish within {JOB_TIMEOUT}s"
                    raise TranscodeError(msg) from err
                await asyncio.to_thread(os.replace, part, final)
            except BaseException:
                with suppress(OSError):
                    await asyncio.to_thread(os.unlink, part)
                raise
            self.logger.debug(
                "Transcoded %s/%s to %s in %.1fs",
                getattr(streamdetails, "provider", "?"),
                getattr(streamdetails, "item_id", "?"),
                spec.name,
                time.monotonic() - started,
            )
        # housekeeping outside the semaphore; a failing trim must not fail the job
        try:
            await self.trim()
        except Exception as err:
            self.logger.warning("Trimming the MAA cache failed: %s", err)
        return final

    async def _transcode(
        self,
        spec: FormatSpec,
        streamdetails: Any,
        pcm_format: Any,
        stream_factory: StreamFactory,
        part: str,
    ) -> None:
        """Run one ffmpeg encoder fed with PCM from the stream factory, writing to part."""
        content_type = pcm_format.content_type
        pcm_codec = getattr(content_type, "value", content_type)
        args = ffmpeg_args(
            spec, str(pcm_codec), int(pcm_format.sample_rate), int(pcm_format.channels), part
        )
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        with suppress(OSError, AttributeError):
            os.setpriority(os.PRIO_PROCESS, proc.pid, ENCODER_NICENESS)
        assert proc.stdin is not None
        assert proc.stderr is not None
        stderr_tail: deque[str] = deque(maxlen=STDERR_TAIL_LINES)

        async def _read_stderr() -> None:
            assert proc.stderr is not None
            while line := await proc.stderr.readline():
                stderr_tail.append(line.decode("utf-8", "replace").rstrip())

        stderr_task = asyncio.create_task(_read_stderr())
        source_error: BaseException | None = None
        fed_bytes = 0
        try:
            try:
                async with aclosing(stream_factory(streamdetails, pcm_format)) as stream:
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

        tail = " | ".join(stderr_tail)
        if source_error is not None:
            msg = f"reading the source failed: {source_error!r}"
            raise TranscodeError(msg) from source_error
        if returncode != 0:
            msg = f"ffmpeg exited with code {returncode}: {tail or 'no output'}"
            raise TranscodeError(msg)
        if fed_bytes == 0:
            msg = "the source delivered no audio"
            raise TranscodeError(msg)
        size = await asyncio.to_thread(_file_size, part)
        if not size:
            msg = f"ffmpeg produced no output: {tail or 'no output'}"
            raise TranscodeError(msg)

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
                    try:
                        os.unlink(path)
                    except OSError:
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
        """Delete every finished cache file (in-flight jobs are left alone)."""
        in_flight = set(self._jobs)

        def _clear() -> tuple[int, int]:
            count = 0
            freed = 0
            for _, size, path, key in self._scan():
                if key in in_flight:
                    continue
                with suppress(OSError):
                    os.unlink(path)
                    count += 1
                    freed += size
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
