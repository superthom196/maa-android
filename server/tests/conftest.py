"""
Test setup: make ``maa.formats`` importable without Music Assistant installed.

The plugin package's ``__init__`` imports Music Assistant internals, so instead of importing
the package normally a bare ``maa`` package module is registered that points at the plugin
directory; submodules without MA imports (formats) then import on their own.
"""

from __future__ import annotations

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
import os, sys, time
data = sys.stdin.buffer.read()
if os.environ.get("FAKE_FFMPEG_FAIL"):
    sys.stderr.write("fake failure line 1\\nfake failure line 2\\n")
    sys.exit(3)
delay = float(os.environ.get("FAKE_FFMPEG_DELAY", "0"))
time.sleep(delay)
with open(sys.argv[-1], "wb") as f:
    f.write(b"ENC:" + data)
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
    monkeypatch.delenv("FAKE_FFMPEG_DELAY", raising=False)
    return exe
