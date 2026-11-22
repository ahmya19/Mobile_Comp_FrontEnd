package com.example.pinpoint.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded decoding avoids loading a camera's full-resolution image into memory. */
object PhotoCompressor {
    suspend fun jpeg(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read the selected image." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode the selected image.")
        try {
            var quality = 85
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Image compression failed." }
                    output.toByteArray()
                }
                quality -= 10
            } while (bytes.size > 256000 && quality >= 15)
            require(bytes.size <= 256000) { "Photo is too detailed. Choose a smaller photo." }
            bytes
        } finally { bitmap.recycle() }
    }
}
