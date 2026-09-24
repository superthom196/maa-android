"""
Output format handling for the MAA plugin.

Pure helpers without any Music Assistant imports, so they can be unit tested on their own:
parsing of the format names the app requests, the passthrough decision, the exact ffmpeg
command lines (plain, normalised, and the two-stage measure/encode), the loudness maths and
the cache key.
"""

from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass

PLUGIN_VERSION = "0.2.0"
API_VERSION = 1

OPUS_MIN_KBPS = 64
OPUS_MAX_KBPS = 320
OPUS_DEFAULT_KBPS = 192
FLAC_NAME = "flac-16-44"

# human readable description of the accepted format names (served by /maa/info)
SUPPORTED_FORMATS = (f"opus-<{OPUS_MIN_KBPS}..{OPUS_MAX_KBPS}>", FLAC_NAME)

_OPUS_RE = re.compile(r"opus-([0-9]{2,3})")

# bumping this invalidates every cached file (the prefix is part of the cache key)
CACHE_KEY_VERSION = "v2"

# --- volume normalisation (mirrors Music Assistant's MEASUREMENT_ONLY mode) -----------------
# Music Assistant's CONF_ENTRY_VOLUME_NORMALIZATION_TARGET range; out-of-range values fall back
NORM_TARGET_RANGE = (-30, -5)
# a measurement at or below this is near-silence and not trusted (MA's
# LOUDNESS_MEASUREMENT_MIN_LUFS); such tracks get no gain
LOUDNESS_FLOOR_LUFS = -50.0
# true-peak ceiling for the output: a limiter is added when the gained peak would exceed it
TRUE_PEAK_LIMIT_DBTP = -1.0
# Opus decoding overshoots the pre-encode true peak on dense material, more at low bitrates
# (measured on the Pi with a limited 96 kHz master: ~0.9 dB at 192k, ~1.4 dB at 128k), so Opus
# keeps that much extra headroom: (minimum kbps, overshoot dB), first match wins
OPUS_OVERSHOOT_DB = ((160, 1.0), (112, 1.5), (0, 2.0))
# true peak assumed when only the integrated loudness is known (a full-scale master)
ASSUMED_TRUE_PEAK_DBTP = 0.0
# the limiter runs 4x oversampled, so its sample-peak ceiling is (nearly) a true-peak ceiling;
# the small extra margin covers the final resample/dither
LIMITER_MARGIN_DB = 0.2
LIMITER_OVERSAMPLE = 4
# brick-wall limiter used only when needed: no auto make-up gain, delay compensated
_ALIMITER = "alimiter=limit={ceiling}dB:attack=5:release=50:level=false:latency=true"
# ebur128 summary (logged at info; per-frame lines only at verbose)
_INTEGRATED_RE = re.compile(
    r"Integrated loudness:.*?I:\s*(-?\d+(?:\.\d+)?|-inf)\s*LUFS", re.DOTALL
)
_TRUE_PEAK_RE = re.compile(r"True peak:.*?Peak:\s*(-?\d+(?:\.\d+)?|-inf)\s*dBFS", re.DOTALL)

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


def true_peak_ceiling(spec: FormatSpec) -> float:
    """Return the highest pre-encode true peak (dBTP) that keeps the output at <= -1 dBTP."""
    if spec.kind != "opus":
        return TRUE_PEAK_LIMIT_DBTP
    kbps = spec.kbps or 0
    overshoot = next(db for min_kbps, db in OPUS_OVERSHOOT_DB if kbps >= min_kbps)
    return TRUE_PEAK_LIMIT_DBTP - overshoot


def output_rate(spec: FormatSpec) -> int:
    """Return the sample rate of the encoded output."""
    return 48000 if spec.kind == "opus" else 44100


def limiter_filters(spec: FormatSpec) -> str:
    """Return the true-peak guard: 4x oversample, then a brick-wall limiter at the ceiling."""
    ceiling = round(true_peak_ceiling(spec) - LIMITER_MARGIN_DB, 2)
    oversampled = output_rate(spec) * LIMITER_OVERSAMPLE
    return f"aresample={oversampled}:{_SOXR.removeprefix('aresample=')}," + _ALIMITER.format(
        ceiling=ceiling
    )


def audio_filters(spec: FormatSpec, gain_db: float | None = None, limiter: bool = False) -> str:
    """
    Return the -af chain: optional gain and limiter (in float), then resample (+ dither).

    Without gain or limiter this is exactly the plain resample/dither chain.
    """
    parts: list[str] = []
    if gain_db is not None and round(gain_db, 2) != 0:
        parts.append(f"volume={gain_db:.2f}dB")
    if limiter:
        parts.append(limiter_filters(spec))
    parts.append(_SOXR if spec.kind == "opus" else f"{_SOXR}:dither_method=triangular")
    return ",".join(parts)


def _encoder_args(spec: FormatSpec, channels: int, filters: str, out_path: str) -> list[str]:
    downmix = ["-ac", "2"] if channels > 2 else []
    if spec.kind == "opus":
        args = [
            "-af",
            filters,
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
        args = [
            "-af",
            filters,
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
    return [*args, "-y", out_path]


def _pcm_input_args(pcm_codec_name: str, sample_rate: int, channels: int) -> list[str]:
    return ["-f", pcm_codec_name, "-ar", str(sample_rate), "-ac", str(channels), "-i", "-"]


def ffmpeg_args(
    spec: FormatSpec,
    pcm_codec_name: str,
    sample_rate: int,
    channels: int,
    out_path: str,
    gain_db: float | None = None,
    limiter: bool = False,
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
    :param gain_db: Normalisation gain, applied in float before resampling/dithering.
    :param limiter: Add the true-peak guard limiter after the gain.
    """
    return [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        *_pcm_input_args(pcm_codec_name, sample_rate, channels),
        *_encoder_args(spec, channels, audio_filters(spec, gain_db, limiter), out_path),
    ]


def measure_args(
    pcm_codec_name: str, sample_rate: int, channels: int, intermediate_path: str
) -> list[str]:
    """
    Return stage 1 of a two-stage normalised transcode.

    Raw PCM on stdin is written unchanged (as 32-bit float, lossless for <=24-bit input) to
    an intermediate WAV (RF64 when large) while ebur128 measures integrated loudness and
    true peak in the same pass; the summary goes to stderr (see parse_ebur128).
    """
    return [
        "ffmpeg",
        "-hide_banner",
        "-nostats",
        "-loglevel",
        "info",
        *_pcm_input_args(pcm_codec_name, sample_rate, channels),
        "-af",
        "ebur128=peak=true:framelog=verbose",
        "-c:a",
        "pcm_f32le",
        "-rf64",
        "auto",
        "-f",
        "wav",
        "-y",
        intermediate_path,
    ]


def encode_file_args(
    spec: FormatSpec,
    in_path: str,
    channels: int,
    out_path: str,
    gain_db: float | None = None,
    limiter: bool = False,
) -> list[str]:
    """Return stage 2: encode the intermediate file with the gain (and limiter) applied."""
    return [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        in_path,
        *_encoder_args(spec, channels, audio_filters(spec, gain_db, limiter), out_path),
    ]


def parse_ebur128(log: str) -> tuple[float | None, float | None]:
    """Return (integrated loudness LUFS, true peak dBTP) from an ebur128 summary log."""

    def _value(pattern: re.Pattern[str]) -> float | None:
        matches = pattern.findall(log)
        if not matches:
            return None
        try:
            value = float(matches[-1])
        except ValueError:
            return None
        return value if math.isfinite(value) else None

    return _value(_INTEGRATED_RE), _value(_TRUE_PEAK_RE)


@dataclass(frozen=True)
class Normalization:
    """The plugin's volume normalisation setting."""

    enabled: bool
    target: int

    @property
    def variant(self) -> str:
        """Return the variant name, part of the cache key and of /maa/info: 'n-14' or 'off'."""
        return f"n{self.target}" if self.enabled else "off"


def compute_gain(target: float, loudness: float | None) -> float | None:
    """Return the gain in dB to bring loudness to target, or None when unknown/unreliable."""
    if loudness is None or not math.isfinite(loudness) or loudness <= LOUDNESS_FLOOR_LUFS:
        return None
    return round(target - loudness, 2)


def needs_limiter(spec: FormatSpec, gain_db: float | None, true_peak: float | None) -> bool:
    """
    Return True when the gained true peak would exceed the format's ceiling.

    The ceiling is -1 dBTP for FLAC and lower before encoding for Opus (codec overshoot, see
    true_peak_ceiling).
    Without a gain nothing is limited: normalisation off means the audio is left alone.
    """
    if gain_db is None:
        return False
    peak = true_peak if true_peak is not None else ASSUMED_TRUE_PEAK_DBTP
    return peak + gain_db > true_peak_ceiling(spec)


def cache_key(
    provider_instance: str,
    prov_item_id: str,
    fmt_name: str,
    version: int | str | None,
    variant: str = "off",
) -> str:
    """
    Return the (stable) cache key for a transcoded file.

    :param provider_instance: Provider instance id the audio is streamed from.
    :param prov_item_id: The provider's own item id.
    :param fmt_name: Canonical format name (FormatSpec.name).
    :param version: A token that changes when the source changes (the plugin passes the
        provider mapping's details, which the filesystem providers set to the file mtime).
    :param variant: The normalisation variant (Normalization.variant, e.g. 'n-14' or 'off').
    """
    version_str = "" if version is None else str(version)
    raw = (
        f"{CACHE_KEY_VERSION}|{provider_instance}|{prov_item_id}|{fmt_name}|{version_str}|{variant}"
    )
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:32]  # noqa: S324 - not security


def source_key(provider_instance: str, prov_item_id: str, version: int | str | None) -> str:
    """Return the key of a source (format independent), used for stored loudness readings."""
    version_str = "" if version is None else str(version)
    raw = f"src|{provider_instance}|{prov_item_id}|{version_str}"
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


@dataclass(frozen=True)
class GainPlan:
    """
    What a transcode job knows about normalisation before it starts.

    ``loudness``/``true_peak`` are set when a reading is already available (from the provider
    or Music Assistant's audio analysis); otherwise the job looks for a stored reading of its
    own under ``source_key`` and, failing that, measures the track in a first pass.
    """

    normalize: bool = False
    target: int | None = None
    loudness: float | None = None
    true_peak: float | None = None
    loudness_source: str = "none"
    source_key: str | None = None
