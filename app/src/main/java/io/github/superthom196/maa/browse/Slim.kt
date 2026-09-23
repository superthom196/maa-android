package io.github.superthom196.maa.browse

import android.util.Log
import io.github.superthom196.maa.data.ItemMapping
import io.github.superthom196.maa.data.MediaItem
import io.github.superthom196.maa.data.MediaItemMetadata

/*
 * What the browse tree keeps on disk. A full library item drags provider mappings, audio formats,
 * descriptions and every image along; the tree needs names, ids, one picture and track numbers.
 * Slimming first keeps the index and list caches a fraction of the size, which matters because
 * they are parsed while Android Auto waits.
 */

internal fun ItemMapping.slim(): ItemMapping =
    ItemMapping(itemId = itemId, provider = provider, name = name, mediaType = mediaType, image = image, year = year)

internal fun MediaItem.slim(mediaType: String = this.mediaType): MediaItem = MediaItem(
    itemId = itemId,
    provider = provider,
    name = name,
    version = version?.takeIf { it.isNotBlank() },
    sortName = sortName,
    mediaType = mediaType,
    isPlayable = isPlayable,
    metadata = thumb?.let { MediaItemMetadata(images = listOf(it)) },
    favorite = favorite,
    year = year,
    artists = artists?.map { it.slim() },
    albumType = albumType,
    duration = duration,
    album = album?.slim(),
    trackNumber = trackNumber,
    discNumber = discNumber,
    owner = owner,
)

/** android.util.Log throws in plain JVM unit tests; the browse classes are tested there. */
internal fun logI(tag: String, msg: String) { runCatching { Log.i(tag, msg) } }
internal fun logW(tag: String, msg: String) { runCatching { Log.w(tag, msg) } }
