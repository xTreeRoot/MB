package com.xtreeroot.mb.preferences

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.AtomicFile
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AppearanceBackgroundStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "appearance-background.png"))

    suspend fun load(): Bitmap? = withContext(Dispatchers.IO) {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return@withContext null }
        input.use { BitmapFactory.decodeStream(it) ?: throw IOException("Invalid appearance background") }
    }

    suspend fun replace(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val request = ImageRequest.Builder(context).data(uri).size(maxDimension, maxDimension)
            .scale(Scale.FIT).precision(Precision.INEXACT).allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED).build()
        val result = context.imageLoader.execute(request) as? SuccessResult
        val decoded = (result?.drawable as? BitmapDrawable)?.bitmap ?: throw IOException("Invalid image")
        val longestSide = maxOf(decoded.width, decoded.height)
        val bitmap = if (longestSide <= maxDimension) decoded else Bitmap.createScaledBitmap(decoded,
            (decoded.width.toLong() * maxDimension / longestSide).toInt().coerceAtLeast(1),
            (decoded.height.toLong() * maxDimension / longestSide).toInt().coerceAtLeast(1), true)
        val output = file.startWrite()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Cannot encode image")
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        bitmap
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        if (file.baseFile.exists()) throw IOException("Cannot remove appearance background")
    }

    private companion object { const val maxDimension = 1600 }
}
