"""
Test setup: make ``maa.formats`` importable without Music Assistant installed.

The plugin package's ``__init__`` imports Music Assistant internals, so instead of importing
the package normally a bare ``maa`` package module is registered that points at the plugin
directory; submodules without MA imports (formats) then import on their own.
"""

from __future__ import annotations

import json
import os
import stat
import sys
import types
from pathlib import Path

import pytest

PLUGIN_DIR = Path(__file__).resolve().parents[1] / "maa"

if "maa" not in sys.modules:
    package = types.ModuleType("maa")
    package.__path__ = [str(PLUGIN_DIR)]
    sys.modules["maa"] = package


FAKE_FFMPEG = f"""#!{sys.executable}
import json, os, sys, time
args = sys.argv[1:]
if log := os.environ.get("FAKE_FFMPEG_LOG"):
    with open(log, "a") as f:
        f.write(json.dumps(args) + "\\n")
src = args[args.index("-i") + 1]
if src == "-":
    data = sys.stdin.buffer.read()
else:
    with open(src, "rb") as f:
        data = f.read()
if os.environ.get("FAKE_FFMPEG_FAIL"):
    sys.stderr.write("fake failure line 1\\nfake failure line 2\\n")
    sys.exit(3)
delay = float(os.environ.get("FAKE_FFMPEG_DELAY", "0"))
time.sleep(delay)
if any("ebur128" in a for a in args):
    # stage 1: the intermediate is the raw input, the summary goes to stderr
    lufs = os.environ.get("FAKE_LUFS", "-8.0")
    tp = os.environ.get("FAKE_TP", "0.5")
    sys.stderr.write(
        "[Parsed_ebur128_0 @ 0x1] Summary:\\n\\n  Integrated loudness:\\n"
        f"    I:         {{lufs}} LUFS\\n    Threshold: -18.0 LUFS\\n\\n"
        "  Loudness range:\\n    LRA:         5.0 LU\\n\\n"
        f"  True peak:\\n    Peak:        {{tp}} dBFS\\n"
    )
    out = data
else:
    out = b"ENC:" + data
with open(args[-1], "wb") as f:
    f.write(out)
"""


@pytest.fixture
def fake_ffmpeg(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    exe = bin_dir / "ffmpeg"
    exe.write_text(FAKE_FFMPEG)
    exe.chmod(exe.stat().st_mode | stat.S_IEXEC)
    monkeypatch.setenv("PATH", f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.delenv("FAKE_FFMPEG_FAIL", raising=False)
    for var in ("FAKE_FFMPEG_DELAY", "FAKE_LUFS", "FAKE_TP"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("FAKE_FFMPEG_LOG", str(tmp_path / "ffmpeg_calls.jsonl"))
    return exe


def ffmpeg_calls(tmp_path: Path) -> list[list[str]]:
    """Return the argument lists of every fake ffmpeg run so far."""
    log = tmp_path / "ffmpeg_calls.jsonl"
    if not log.exists():
        return []
    return [json.loads(line) for line in log.read_text().splitlines() if line]
