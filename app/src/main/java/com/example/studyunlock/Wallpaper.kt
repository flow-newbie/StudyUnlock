package com.example.studyunlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt

internal val WallpaperActive = compositionLocalOf { false }

internal class WallpaperFile(context: Context) {
    private val resolver = context.contentResolver
    private val file = AtomicFile(File(context.noBackupFilesDir, "wallpaper.jpg"))

    fun load(): Bitmap? = runCatching {
        file.openRead().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    fun decode(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val scale = minOf(1.0, 2560.0 / maxOf(width, height))
        decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    fun save(bitmap: Bitmap) {
        val stream = file.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun reset() {
        file.delete()
        check(!file.baseFile.exists())
    }
}

@Composable
internal fun WallpaperPreview(bitmap: Bitmap, ratio: Float = 2.3f) {
    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(ratio)
        .clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
}
