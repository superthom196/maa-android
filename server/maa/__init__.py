"""
MAA (Android Auto offline cache) plugin for Music Assistant.

Serves whole, seekable, transcoded track files over the Music Assistant webserver so the
MAA Android Auto app can cache tracks ahead of playback. See routes.py for the HTTP API.
"""

from __future__ import annotations

import logging
import os
from typing import TYPE_CHECKING

from .formats import NORM_TARGET_RANGE, FormatSpec, Normalization, configured_format

LOGGER = logging.getLogger("music_assistant.providers.maa")

try:
    from music_assistant_models.config_entries import (
        ConfigActionResult,
        ConfigEntry,
        ConfigValueOption,
    )
    from music_assistant_models.enums import ConfigEntryType

    from music_assistant.constants import (
        CONF_ENTRY_VOLUME_NORMALIZATION_TARGET,
        CONF_VOLUME_NORMALIZATION_TARGET,
    )
    from music_assistant.models.plugin import PluginProvider

    from .routes import MaaApi
    from .transcode import TranscodeCache
except ImportError as _err:
    LOGGER.error(
        "MAA plugin: importing Music Assistant internals failed (%s). This plugin was "
        "written against Music Assistant 2.10.x; the installed version is not compatible.",
        _err,
    )
    raise

if TYPE_CHECKING:
    from collections.abc import Callable

    from music_assistant_models.config_entries import ProviderConfig
    from music_assistant_models.provider import ProviderManifest

    from music_assistant.mass import MusicAssistant
    from music_assistant.models import ProviderInstanceType

CONF_OUTPUT_FORMAT = "output_format"
CONF_OPUS_BITRATE = "opus_bitrate"
CONF_CACHE_MAX_GB = "cache_max_gb"
CONF_ACTION_CLEAR_CACHE = "clear_cache"
CONF_VOLUME_NORMALIZATION = "volume_normalization"
CONF_TARGET_LOUDNESS = "target_loudness"

DEFAULT_OUTPUT_FORMAT = "opus"
DEFAULT_OPUS_BITRATE = 192
DEFAULT_CACHE_MAX_GB = 2
CACHE_SUBDIR = "maa"
GIB = 1024**3


async def setup(
    mass: MusicAssistant, manifest: ProviderManifest, config: ProviderConfig
) -> ProviderInstanceType:
    """Initialize provider(instance) with given configuration."""
    return MaaProvider(mass, manifest, config)


class MaaProvider(PluginProvider):
    """Plugin that serves transcoded whole-file tracks for the MAA Android Auto app."""

    # NOTE: not named "cache": the Provider base class uses self.cache for mass.cache
    transcode_cache: TranscodeCache
    _unregister_routes: list[Callable[[], None]]

    async def get_config_entries(self) -> tuple[ConfigEntry, ...]:
        """
        Return the (options) config entries for this plugin.

        Labels and descriptions are set here as well as in strings.json: an out-of-tree
        plugin's strings.json is not compiled into Music Assistant's translations, and the
        values set here are kept when no translation exists.
        """
        return (
            ConfigEntry(
                key=CONF_OUTPUT_FORMAT,
                type=ConfigEntryType.STRING,
                label="Output format",
                description=(
                    "Format of the files served to the app. Opus is small and transparent at "
                    "192 kbps or more; FLAC 16-bit / 44.1 kHz is lossless at CD quality "
                    "(with normalisation off, CD-quality FLAC files are served untouched)."
                ),
                default_value=DEFAULT_OUTPUT_FORMAT,
                options=[
                    ConfigValueOption("opus", title="Opus (Ogg)"),
                    ConfigValueOption("flac", title="FLAC 16-bit / 44.1 kHz"),
                ],
                required=True,
            ),
            ConfigEntry(
                key=CONF_OPUS_BITRATE,
                type=ConfigEntryType.INTEGER,
                label="Opus bitrate (kbps)",
                description="Target bitrate for Opus output (VBR), 64 to 320 kbps.",
                default_value=DEFAULT_OPUS_BITRATE,
                range=(64, 320),
                depends_on=CONF_OUTPUT_FORMAT,
                depends_on_value="opus",
                required=True,
            ),
            ConfigEntry(
                key=CONF_VOLUME_NORMALIZATION,
                type=ConfigEntryType.BOOLEAN,
                label="Volume normalisation",
                description=(
                    "Bring every track to the same loudness, like Music Assistant does for its "
                    "own players. Uses Music Assistant's loudness analysis when it has one, "
                    "otherwise measures the track (EBU R128) before encoding. A limiter is "
                    "added only when a boosted track would peak above -1 dBTP. When off, "
                    "tracks are served at their original level (and CD-quality FLAC as-is)."
                ),
                default_value=True,
                required=True,
            ),
            ConfigEntry(
                key=CONF_TARGET_LOUDNESS,
                type=ConfigEntryType.INTEGER,
                label="Target loudness (LUFS)",
                description=(
                    "Loudness to normalise to. Defaults to Music Assistant's own volume "
                    "normalisation target (Settings > Core > Streams)."
                ),
                default_value=self._ma_target_loudness(),
                range=self._target_range(),
                depends_on=CONF_VOLUME_NORMALIZATION,
                required=True,
            ),
            ConfigEntry(
                key=CONF_CACHE_MAX_GB,
                type=ConfigEntryType.INTEGER,
                label="Maximum cache size (GB)",
                description=(
                    "Disk space the server may use for transcoded files. The least recently "
                    "used files are removed when the cache grows beyond this size."
                ),
                default_value=DEFAULT_CACHE_MAX_GB,
                range=(1, 50),
                required=True,
            ),
            ConfigEntry(
                key=CONF_ACTION_CLEAR_CACHE,
                type=ConfigEntryType.ACTION,
                label="Clear transcode cache",
                description="Delete all transcoded files stored on the server.",
                action=CONF_ACTION_CLEAR_CACHE,
                action_label="Clear cache",
                required=False,
            ),
        )

    @property
    def configured_format(self) -> FormatSpec:
        """Return the output format configured for this plugin."""
        return configured_format(
            self.get_config_value(CONF_OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT),
            self.get_config_value(CONF_OPUS_BITRATE, DEFAULT_OPUS_BITRATE),
        )

    def _target_range(self) -> tuple[int, int]:
        """Return Music Assistant's allowed range for the normalisation target."""
        return CONF_ENTRY_VOLUME_NORMALIZATION_TARGET.range or NORM_TARGET_RANGE

    def _valid_target(self, value: object) -> int | None:
        """Return value as a target in range (MA's own check: low <= v < high), else None."""
        try:
            target = int(value)  # type: ignore[call-overload]
        except (TypeError, ValueError):
            return None
        low, high = self._target_range()
        return target if low <= target < high else None

    def _ma_target_loudness(self) -> int:
        """Return Music Assistant's global volume normalisation target (LUFS)."""
        try:
            value = self.mass.streams.get_config_value(CONF_VOLUME_NORMALIZATION_TARGET)
        except Exception:
            value = None
        if (target := self._valid_target(value)) is not None:
            return target
        default = self._valid_target(CONF_ENTRY_VOLUME_NORMALIZATION_TARGET.default_value)
        return default if default is not None else -14

    @property
    def normalization(self) -> Normalization:
        """Return the configured volume normalisation."""
        enabled = bool(self.get_config_value(CONF_VOLUME_NORMALIZATION, True))
        target = self._valid_target(self.get_config_value(CONF_TARGET_LOUDNESS, None))
        if target is None:
            target = self._ma_target_loudness()
        return Normalization(enabled=enabled, target=target)

    @property
    def cache_max_bytes(self) -> int:
        """Return the configured maximum cache size in bytes."""
        try:
            max_gb = int(self.get_config_value(CONF_CACHE_MAX_GB, DEFAULT_CACHE_MAX_GB))
        except (TypeError, ValueError):
            max_gb = DEFAULT_CACHE_MAX_GB
        return max(max_gb, 1) * GIB

    async def handle_async_init(self) -> None:
        """Set up the cache directory and register the HTTP routes."""
        self._unregister_routes = []
        cache_dir = os.path.join(self.mass.cache_path, CACHE_SUBDIR)
        self.transcode_cache = TranscodeCache(
            cache_dir, self.cache_max_bytes, logger=self.logger.getChild("transcode")
        )
        await self.transcode_cache.setup()
        self._unregister_routes = MaaApi(self).register()
        self.logger.info(
            "MAA plugin ready: format %s, normalisation %s, cache %s (max %s GB)",
            self.configured_format.name,
            self.normalization.variant,
            cache_dir,
            self.cache_max_bytes // GIB,
        )

    async def unload(self, is_removed: bool = False) -> None:
        """Unregister the routes and stop running transcodes."""
        await super().unload(is_removed)
        for unregister in getattr(self, "_unregister_routes", []):
            unregister()
        self._unregister_routes = []
        if (cache := getattr(self, "transcode_cache", None)) is not None:
            await cache.close()
            if is_removed:
                await cache.remove_all()

    async def handle_config_action(
        self, action: str
    ) -> tuple[ConfigEntry, ...] | ConfigActionResult | None:
        """Handle the clear cache button."""
        if action == CONF_ACTION_CLEAR_CACHE:
            if (cache := getattr(self, "transcode_cache", None)) is None:
                return ConfigActionResult(message="The plugin is not loaded, nothing to clear.")
            files, freed = await cache.clear()
            self.logger.info("Cleared MAA cache: %s files, %s bytes", files, freed)
            return ConfigActionResult(
                message=f"Removed {files} cached file(s), {freed / 1024**2:.0f} MB freed."
            )
        return await super().handle_config_action(action)
