"""Unit tests for maa.formats (pure, no Music Assistant needed)."""

from __future__ import annotations

import hashlib

import pytest

from maa import formats
from maa.formats import (
    FormatSpec,
    cache_key,
    can_passthrough,
    configured_format,
    ffmpeg_args,
    parse,
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
    raw = "v1|filesystem_local--abc|Artist/Album/01 Song.flac|opus-192|1700000000"
    expected = hashlib.sha1(raw.encode()).hexdigest()[:32]  # noqa: S324
    assert (
        cache_key("filesystem_local--abc", "Artist/Album/01 Song.flac", "opus-192", "1700000000")
        == expected
    )


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
