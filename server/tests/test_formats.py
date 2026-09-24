"""Unit tests for maa.formats (pure, no Music Assistant needed)."""

from __future__ import annotations

import hashlib

import pytest

from maa import formats
from maa.formats import (
    FormatSpec,
    Normalization,
    audio_filters,
    cache_key,
    can_passthrough,
    compute_gain,
    configured_format,
    encode_file_args,
    ffmpeg_args,
    limiter_filters,
    measure_args,
    needs_limiter,
    parse,
    parse_ebur128,
    source_key,
    true_peak_ceiling,
)

OPUS = FormatSpec(kind="opus", kbps=192)
FLAC = FormatSpec(kind="flac")


# ---------------------------------------------------------------- constants


def test_versions() -> None:
    assert formats.PLUGIN_VERSION == "0.1.0"
    assert formats.API_VERSION == 1
    assert formats.SUPPORTED_FORMATS == ("opus-<64..320>", "flac-16-44")


# ---------------------------------------------------------------- parse


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("opus-64", FormatSpec("opus", 64)),
        ("opus-96", FormatSpec("opus", 96)),
        ("opus-192", FormatSpec("opus", 192)),
        ("opus-320", FormatSpec("opus", 320)),
        ("flac-16-44", FormatSpec("flac", None)),
    ],
)
def test_parse_valid(value: str, expected: FormatSpec) -> None:
    assert parse(value) == expected
    assert parse(value).name == value  # type: ignore[union-attr]


@pytest.mark.parametrize(
    "value",
    [
        None,
        "",
        "opus",
        "opus-",
        "opus-63",
        "opus-321",
        "opus-0",
        "opus-064",
        "opus-1000",
        "opus-192k",
        "opus--192",
        "opus-19.2",
        "OPUS-192",
        " opus-192",
        "opus-192 ",
        "opus-192\n",
        "flac",
        "flac-16",
        "flac-24-96",
        "flac-16-48",
        "FLAC-16-44",
        "mp3-320",
        "aac-256",
        192,
    ],
)
def test_parse_invalid(value: object) -> None:
    assert parse(value) is None  # type: ignore[arg-type]


# ---------------------------------------------------------------- spec properties


def test_opus_spec_properties() -> None:
    assert OPUS.name == "opus-192"
    assert OPUS.ext == "opus"
    assert OPUS.mime == "audio/ogg"


def test_flac_spec_properties() -> None:
    assert FLAC.name == "flac-16-44"
    assert FLAC.ext == "flac"
    assert FLAC.mime == "audio/flac"


def test_spec_is_frozen_and_hashable() -> None:
    with pytest.raises(AttributeError):
        OPUS.kbps = 128  # type: ignore[misc]
    assert {OPUS, FormatSpec("opus", 192)} == {OPUS}


# ---------------------------------------------------------------- can_passthrough

_OK = {
    "content_type": "flac",
    "bit_depth": 16,
    "sample_rate": 44100,
    "channels": 2,
    "stream_type": "local_file",
    "path": "/media/Hifi/a.flac",
}


def test_passthrough_cd_quality_flac() -> None:
    assert can_passthrough(FLAC, **_OK) is True


def test_passthrough_mono_and_low_bit_depth() -> None:
    assert can_passthrough(FLAC, **{**_OK, "channels": 1}) is True
    assert can_passthrough(FLAC, **{**_OK, "bit_depth": 8}) is True


def test_passthrough_accepts_str_enum_values() -> None:
    from enum import StrEnum

    class Ct(StrEnum):
        FLAC = "flac"

    class St(StrEnum):
        LOCAL_FILE = "local_file"

    assert can_passthrough(
        FLAC, **{**_OK, "content_type": Ct.FLAC, "stream_type": St.LOCAL_FILE}
    )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("content_type", "mp3"),
        ("content_type", "ogg"),
        ("content_type", "alac"),
        ("content_type", None),
        ("bit_depth", 24),
        ("bit_depth", 32),
        ("bit_depth", None),
        ("sample_rate", 48000),
        ("sample_rate", 96000),
        ("sample_rate", 22050),
        ("sample_rate", None),
        ("channels", 6),
        ("channels", 3),
        ("channels", None),
        ("stream_type", "http"),
        ("stream_type", "custom"),
        ("stream_type", None),
        ("path", None),
        ("path", ""),
        ("path", ["/media/a.flac"]),
        ("path", 123),
    ],
)
def test_passthrough_rejects(field: str, value: object) -> None:
    assert can_passthrough(FLAC, **{**_OK, field: value}) is False


def test_passthrough_never_for_opus() -> None:
    assert can_passthrough(OPUS, **_OK) is False


# ---------------------------------------------------------------- ffmpeg_args


def test_ffmpeg_args_opus_exact() -> None:
    assert ffmpeg_args(OPUS, "s24le", 96000, 2, "/c/k.part") == [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "s24le",
        "-ar",
        "96000",
        "-ac",
        "2",
        "-i",
        "-",
        "-af",
        "aresample=resampler=soxr:precision=28",
        "-ar",
        "48000",
        "-c:a",
        "libopus",
        "-b:a",
        "192k",
        "-vbr",
        "on",
        "-application",
        "audio",
        "-f",
        "ogg",
        "-y",
        "/c/k.part",
    ]


def test_ffmpeg_args_flac_exact() -> None:
    assert ffmpeg_args(FLAC, "s16le", 44100, 1, "/c/k.part") == [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "s16le",
        "-ar",
        "44100",
        "-ac",
        "1",
        "-i",
        "-",
        "-af",
        "aresample=resampler=soxr:precision=28:dither_method=triangular",
        "-ar",
        "44100",
        "-sample_fmt",
        "s16",
        "-c:a",
        "flac",
        "-compression_level",
        "5",
        "-f",
        "flac",
        "-y",
        "/c/k.part",
    ]


def test_ffmpeg_args_bitrate_follows_spec() -> None:
    args = ffmpeg_args(FormatSpec("opus", 64), "s16le", 44100, 2, "/x")
    assert args[args.index("-b:a") + 1] == "64k"


@pytest.mark.parametrize("spec", [OPUS, FLAC])
def test_ffmpeg_args_downmix_multichannel(spec: FormatSpec) -> None:
    args = ffmpeg_args(spec, "s24le", 48000, 6, "/x")
    in_idx = args.index("-i")
    # input keeps the real layout, output is downmixed after -i
    assert args[args.index("-ac") + 1] == "6"
    assert args.index("-ac") < in_idx
    out = args[in_idx:]
    assert out[out.index("-ac") + 1] == "2"
    assert args[-2:] == ["-y", "/x"]


@pytest.mark.parametrize("channels", [1, 2])
@pytest.mark.parametrize("spec", [OPUS, FLAC])
def test_ffmpeg_args_no_downmix_for_stereo_or_mono(spec: FormatSpec, channels: int) -> None:
    args = ffmpeg_args(spec, "s16le", 44100, channels, "/x")
    assert args.count("-ac") == 1


# ---------------------------------------------------------------- cache_key


def test_cache_key_known_value() -> None:
    raw = "v2|filesystem_local--abc|Artist/Album/01 Song.flac|opus-192|1700000000|n-14"
    expected = hashlib.sha1(raw.encode()).hexdigest()[:32]  # noqa: S324
    assert (
        cache_key(
            "filesystem_local--abc", "Artist/Album/01 Song.flac", "opus-192", "1700000000", "n-14"
        )
        == expected
    )


def test_cache_key_variants_never_collide() -> None:
    keys = {
        cache_key("p", "i", "opus-192", "1", variant)
        for variant in ("off", "n-14", "n-17", "n-23")
    }
    assert len(keys) == 4
    # the default variant is "off"
    assert cache_key("p", "i", "opus-192", "1") == cache_key("p", "i", "opus-192", "1", "off")
    # and the v2 key never equals the v0.1.0 (v1) key of the same file
    v1 = hashlib.sha1(b"v1|p|i|opus-192|1").hexdigest()[:32]  # noqa: S324
    assert v1 not in keys


def test_source_key_is_format_and_variant_independent() -> None:
    assert source_key("p", "i", "1") == source_key("p", "i", 1)
    assert source_key("p", "i", "1") != source_key("p", "i", "2")
    assert len(source_key("p", "i", None)) == 32


def test_cache_key_shape_and_stability() -> None:
    key = cache_key("prov", "item", "flac-16-44", 123)
    assert len(key) == 32
    assert all(c in "0123456789abcdef" for c in key)
    assert key == cache_key("prov", "item", "flac-16-44", 123)
    # int and str versions of the same token are the same key
    assert key == cache_key("prov", "item", "flac-16-44", "123")


def test_cache_key_none_version_equals_empty() -> None:
    assert cache_key("p", "i", "opus-192", None) == cache_key("p", "i", "opus-192", "")


@pytest.mark.parametrize(
    "other",
    [
        ("p2", "i", "opus-192", "1"),
        ("p", "i2", "opus-192", "1"),
        ("p", "i", "opus-128", "1"),
        ("p", "i", "flac-16-44", "1"),
        ("p", "i", "opus-192", "2"),
    ],
)
def test_cache_key_changes_with_every_part(other: tuple[str, str, str, str]) -> None:
    assert cache_key("p", "i", "opus-192", "1") != cache_key(*other)


# ---------------------------------------------------------------- configured_format


@pytest.mark.parametrize(
    ("output_format", "bitrate", "expected"),
    [
        ("opus", 192, "opus-192"),
        ("opus", 64, "opus-64"),
        ("opus", 320, "opus-320"),
        ("opus", 10, "opus-64"),
        ("opus", 999, "opus-320"),
        ("opus", "160", "opus-160"),
        ("opus", None, "opus-192"),
        ("opus", "junk", "opus-192"),
        ("flac", 192, "flac-16-44"),
        (None, None, "opus-192"),
    ],
)
def test_configured_format(output_format: object, bitrate: object, expected: str) -> None:
    assert configured_format(output_format, bitrate).name == expected


# ---------------------------------------------------------------- normalisation


def test_normalization_variant() -> None:
    assert Normalization(enabled=True, target=-14).variant == "n-14"
    assert Normalization(enabled=True, target=-17).variant == "n-17"
    assert Normalization(enabled=False, target=-17).variant == "off"


@pytest.mark.parametrize(
    ("target", "loudness", "expected"),
    [
        (-14, -8.0, -6.0),
        (-14, -8.37, -5.63),
        (-17, -23.5, 6.5),
        (-14, -14.0, 0.0),
        (-14, None, None),
        (-14, -50.0, None),  # at MA's reliability floor
        (-14, -70.0, None),
        (-14, float("-inf"), None),
        (-14, float("nan"), None),
    ],
)
def test_compute_gain(target: int, loudness: float | None, expected: float | None) -> None:
    assert compute_gain(target, loudness) == expected


@pytest.mark.parametrize(
    ("gain", "true_peak", "expected"),
    [
        (-6.0, 0.5, False),  # loud master turned down
        (-1.0, 0.5, True),  # -0.5 dBTP after gain
        (-1.5, 0.5, False),  # exactly -1.0 is allowed
        (6.0, -10.0, False),  # quiet track boosted, still -4 dBTP
        (6.0, -6.5, True),
        (0.5, None, True),  # unknown peak: a full-scale master is assumed
        (-1.0, None, False),
        (-0.5, None, True),
        (None, 3.0, False),  # no gain, nothing to guard
    ],
)
def test_needs_limiter_flac(gain: float | None, true_peak: float | None, expected: bool) -> None:
    assert needs_limiter(FLAC, gain, true_peak) is expected


@pytest.mark.parametrize(
    ("kbps", "ceiling"),
    [(320, -2.0), (192, -2.0), (160, -2.0), (128, -2.5), (112, -2.5), (96, -3.0), (64, -3.0)],
)
def test_opus_keeps_headroom_for_codec_overshoot(kbps: int, ceiling: float) -> None:
    spec = FormatSpec("opus", kbps)
    assert true_peak_ceiling(spec) == ceiling
    assert needs_limiter(spec, -1.0, ceiling + 1.0) is False  # lands exactly on the ceiling
    assert needs_limiter(spec, -1.0, ceiling + 1.1) is True
    assert true_peak_ceiling(FLAC) == -1.0


def test_limiter_filters_oversample_and_ceiling() -> None:
    assert limiter_filters(OPUS) == (
        "aresample=192000:resampler=soxr:precision=28,"
        "alimiter=limit=-2.2dB:attack=5:release=50:level=false:latency=true"
    )
    assert limiter_filters(FLAC) == (
        "aresample=176400:resampler=soxr:precision=28,"
        "alimiter=limit=-1.2dB:attack=5:release=50:level=false:latency=true"
    )


def test_audio_filters() -> None:
    soxr = "aresample=resampler=soxr:precision=28"
    assert audio_filters(OPUS) == soxr
    assert audio_filters(OPUS, 0.0) == soxr
    assert audio_filters(OPUS, 0.004) == soxr
    assert audio_filters(OPUS, -6.0) == f"volume=-6.00dB,{soxr}"
    assert (
        audio_filters(OPUS, 5.5, limiter=True) == f"volume=5.50dB,{limiter_filters(OPUS)},{soxr}"
    )
    assert (
        audio_filters(FLAC, -3.456)
        == f"volume=-3.46dB,{soxr}:dither_method=triangular"
    )


def test_ffmpeg_args_with_gain_and_limiter() -> None:
    args = ffmpeg_args(OPUS, "s24le", 96000, 2, "/c/k.part", gain_db=4.2, limiter=True)
    af = args[args.index("-af") + 1]
    # gain and limiter run in float before the resampler
    assert af == f"volume=4.20dB,{limiter_filters(OPUS)},aresample=resampler=soxr:precision=28"
    assert args[:12] == ffmpeg_args(OPUS, "s24le", 96000, 2, "/c/k.part")[:12]


def test_ffmpeg_args_without_gain_unchanged() -> None:
    assert ffmpeg_args(FLAC, "s16le", 44100, 2, "/x", gain_db=None) == ffmpeg_args(
        FLAC, "s16le", 44100, 2, "/x"
    )


def test_measure_args_exact() -> None:
    assert measure_args("s24le", 96000, 2, "/c/k.measure.wav") == [
        "ffmpeg",
        "-hide_banner",
        "-nostats",
        "-loglevel",
        "info",
        "-f",
        "s24le",
        "-ar",
        "96000",
        "-ac",
        "2",
        "-i",
        "-",
        "-af",
        "ebur128=peak=true:framelog=verbose",
        "-c:a",
        "pcm_f32le",
        "-rf64",
        "auto",
        "-f",
        "wav",
        "-y",
        "/c/k.measure.wav",
    ]


def test_encode_file_args_exact() -> None:
    assert encode_file_args(OPUS, "/c/k.measure.wav", 6, "/c/k.part", -6.0) == [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        "/c/k.measure.wav",
        "-af",
        "volume=-6.00dB,aresample=resampler=soxr:precision=28",
        "-ar",
        "48000",
        "-ac",
        "2",
        "-c:a",
        "libopus",
        "-b:a",
        "192k",
        "-vbr",
        "on",
        "-application",
        "audio",
        "-f",
        "ogg",
        "-y",
        "/c/k.part",
    ]


EBUR128_LOG = """Input #0, s24le, from 'fd:':
  Stream #0:0: Audio: pcm_s24le, 96000 Hz, stereo, s32, 4608 kb/s
[Parsed_ebur128_0 @ 0x7f90001d90] t: 0.1  TARGET:-23 LUFS    M:-120.7 S:-120.7     I: -70.0 LUFS
[Parsed_ebur128_0 @ 0x7f90001d90] Summary:

  Integrated loudness:
    I:          -8.3 LUFS
    Threshold: -18.4 LUFS

  Loudness range:
    LRA:         4.1 LU
    Threshold: -28.4 LUFS
    LRA low:   -10.9 LUFS
    LRA high:   -6.8 LUFS

  True peak:
    Peak:        0.6 dBFS
[out#0/wav @ 0x558a02ed20] video:0KiB audio:431KiB subtitle:0KiB other streams:0KiB
"""


def test_parse_ebur128_summary() -> None:
    assert parse_ebur128(EBUR128_LOG) == (-8.3, 0.6)


def test_parse_ebur128_missing_or_silent() -> None:
    assert parse_ebur128("") == (None, None)
    assert parse_ebur128("ffmpeg crashed") == (None, None)
    silent = EBUR128_LOG.replace("-8.3 LUFS", "-inf LUFS").replace("0.6 dBFS", "-inf dBFS")
    assert parse_ebur128(silent) == (None, None)
