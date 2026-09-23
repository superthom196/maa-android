"""
Output format handling for the MAA plugin.

Pure helpers without any Music Assistant imports, so they can be unit tested on their own:
parsing of the format names the app requests, the passthrough decision, the exact ffmpeg
command line per format and the cache key.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

PLUGIN_VERSION = "0.1.0"
API_VERSION = 1

OPUS_MIN_KBPS = 64
OPUS_MAX_KBPS = 320
OPUS_DEFAULT_KBPS = 192
FLAC_NAME = "flac-16-44"

# human readable description of the accepted format names (served by /maa/info)
SUPPORTED_FORMATS = (f"opus-<{OPUS_MIN_KBPS}..{OPUS_MAX_KBPS}>", FLAC_NAME)

_OPUS_RE = re.compile(r"opus-([0-9]{2,3})")

# bumping this invalidates every cached file (the prefix is part of the cache key)
CACHE_KEY_VERSION = "v1"

# resampler settings shared by both encoders (libsoxr is in the MA container's ffmpeg)
_SOXR = "aresample=resampler=soxr:precision=28"


@dataclass(frozen=True)
class FormatSpec:
    """An output format the plugin can deliver."""

    kind: str  # "opus" | "flac"
    kbps: int | None = None  # opus bitrate, None for flac

    @property
    def name(self) -> str:
        """Return the canonical format name, e.g. 'opus-192' or 'flac-16-44'."""
        if self.kind == "opus":
            return f"opus-{self.kbps}"
        return FLAC_NAME

    @property
    def ext(self) -> str:
        """Return the file extension for this format."""
        return "opus" if self.kind == "opus" else "flac"

    @property
    def mime(self) -> str:
        """Return the MIME type for this format."""
        return "audio/ogg" if self.kind == "opus" else "audio/flac"


def parse(value: str | None) -> FormatSpec | None:
    """
    Parse a format name as requested by the app.

    Accepts 'opus-<64..320>' (kbps, no leading zeros) and 'flac-16-44'; anything else
    returns None.
    """
    if not isinstance(value, str):
        return None
    if value == FLAC_NAME:
        return FormatSpec(kind="flac")
    if match := _OPUS_RE.fullmatch(value):
        digits = match.group(1)
        if digits.startswith("0"):
            return None
        kbps = int(digits)
        if OPUS_MIN_KBPS <= kbps <= OPUS_MAX_KBPS:
            return FormatSpec(kind="opus", kbps=kbps)
    return None


def can_passthrough(
    spec: FormatSpec,
    content_type: str | None,
    bit_depth: int | None,
    sample_rate: int | None,
    channels: int | None,
    stream_type: str | None,
    path: object,
) -> bool:
    """
    Return True when the source file can be served as-is for the requested format.

    Only a local FLAC file that already is 16-bit (or less), 44.1 kHz and at most stereo
    qualifies, and only when FLAC output was requested.
    """
    return (
        spec.kind == "flac"
        and stream_type == "local_file"
        and isinstance(path, str)
        and bool(path)
        and content_type == "flac"
        and bit_depth is not None
        and bit_depth <= 16
        and sample_rate == 44100
        and channels is not None
        and channels <= 2
    )


def ffmpeg_args(
    spec: FormatSpec, pcm_codec_name: str, sample_rate: int, channels: int, out_path: str
) -> list[str]:
    """
    Return the ffmpeg command line that encodes raw PCM on stdin into out_path.

    Writing to a real (seekable) file lets ffmpeg go back and finalize the headers (FLAC
    STREAMINFO total samples and seektable, proper Ogg page granules), which is what makes
    the result seekable for the app.

    :param spec: The output format.
    :param pcm_codec_name: The raw PCM input format, e.g. 's24le'.
    :param sample_rate: Sample rate of the PCM input.
    :param channels: Channel count of the PCM input; more than 2 is downmixed to stereo.
    :param out_path: Destination file.
    """
    args = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        pcm_codec_name,
        "-ar",
        str(sample_rate),
        "-ac",
        str(channels),
        "-i",
        "-",
    ]
    downmix = ["-ac", "2"] if channels > 2 else []
    if spec.kind == "opus":
        args += [
            "-af",
            _SOXR,
            "-ar",
            "48000",
            *downmix,
            "-c:a",
            "libopus",
            "-b:a",
            f"{spec.kbps}k",
            "-vbr",
            "on",
            "-application",
            "audio",
            "-f",
            "ogg",
        ]
    else:
        args += [
            "-af",
            f"{_SOXR}:dither_method=triangular",
            "-ar",
            "44100",
            *downmix,
            "-sample_fmt",
            "s16",
            "-c:a",
            "flac",
            "-compression_level",
            "5",
            "-f",
            "flac",
        ]
    args += ["-y", out_path]
    return args


def cache_key(
    provider_instance: str, prov_item_id: str, fmt_name: str, version: int | str | None
) -> str:
    """
    Return the (stable) cache key for a transcoded file.

    :param provider_instance: Provider instance id the audio is streamed from.
    :param prov_item_id: The provider's own item id.
    :param fmt_name: Canonical format name (FormatSpec.name).
    :param version: A token that changes when the source changes (the plugin passes the
        provider mapping's details, which the filesystem providers set to the file mtime).
    """
    version_str = "" if version is None else str(version)
    raw = f"{CACHE_KEY_VERSION}|{provider_instance}|{prov_item_id}|{fmt_name}|{version_str}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:32]  # noqa: S324 - not security


def configured_format(output_format: object, opus_bitrate: object) -> FormatSpec:
    """
    Return the FormatSpec for the plugin's configured output format.

    Falls back to Opus at the default bitrate for missing or out of range values.
    """
    if output_format == "flac":
        return FormatSpec(kind="flac")
    try:
        kbps = int(opus_bitrate)  # type: ignore[call-overload]
    except (TypeError, ValueError):
        kbps = OPUS_DEFAULT_KBPS
    kbps = min(max(kbps, OPUS_MIN_KBPS), OPUS_MAX_KBPS)
    return FormatSpec(kind="opus", kbps=kbps)
