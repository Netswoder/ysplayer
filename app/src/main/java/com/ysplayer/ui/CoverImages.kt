package com.ysplayer.ui

import android.content.Context
import coil.request.ImageRequest
import coil.size.Precision
import com.ysplayer.data.Work

/** The same sized cache key is used by visible images and look-ahead requests. */
internal fun coverRequest(context: Context, work: Work?, pixels: Int): ImageRequest = ImageRequest.Builder(context)
    .data(work?.cover?.ifEmpty { work.autoCover }?.takeIf { it.isNotBlank() })
    .size(pixels).precision(Precision.EXACT)
    .memoryCacheKey("cover:$pixels:${work?.cover?.ifEmpty { work.autoCover }.orEmpty()}")
    .crossfade(false).build()
