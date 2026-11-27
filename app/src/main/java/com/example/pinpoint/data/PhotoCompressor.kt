package com.example.pinpoint.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded decoding avoids loading a camera's full-resolution image into memory. */
object PhotoCompressor {
    private const val MAX_EDGE = 1280
    @Volatile private var forceFallback = false
    /** Debug builds only: skips BitmapFactory so tests can exercise the ImageDecoder fallback deterministically. */
    fun forceFallbackForTesting(enabled: Boolean) {
        check(com.example.pinpoint.BuildConfig.DEBUG) { "Test hook is available in debug builds only." }
        forceFallback = enabled
    }

    /** Small on-screen preview with the camera's EXIF rotation applied, so a portrait shot is not shown sideways. Null if unreadable. */
    fun preview(context: Context, uri: Uri, maxEdge: Int = 640): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@runCatching null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        upright(decoded, orientation)
    }.getOrNull()

    suspend fun jpeg(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // 1. BitmapFactory with a bounded sample size (fast, works for every JPEG/PNG/WebP we have met).
        var bitmap: Bitmap? = null
        var boundsRead = false
        if (!forceFallback) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            boundsRead = bounds.outWidth > 0 && bounds.outHeight > 0
            if (boundsRead) {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (decoded != null) {
                    // Re-encoding drops EXIF, so a camera's orientation tag must be applied to the pixels first or the photo
                    // is stored sideways. Dropping the tag also keeps GPS and other metadata out of the upload.
                    val orientation = runCatching {
                        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
                    bitmap = upright(decoded, orientation)
                }
            }
        }
        // 2. Fallback (Android 9+): some real photos (newer camera formats and encodings) read fine for their size but BitmapFactory
        //    returns nothing. ImageDecoder decodes them straight to a bounded size and applies the orientation itself.
        if (bitmap == null && Build.VERSION.SDK_INT >= 28) {
            bitmap = try { decodeWithImageDecoder(resolver, uri) }
            catch (error: java.io.FileNotFoundException) { throw error }                       // a vanished source is not a corrupt one
            catch (error: java.io.IOException) { null }
            catch (error: RuntimeException) { null }
        }
        if (bitmap == null) {
            if (boundsRead && !forceFallback) error("Cannot decode the selected image.")
            throw IllegalArgumentException("Cannot read the selected image.")
        }
        val image: Bitmap = bitmap
        try {
            var quality = 85
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { output ->
                    check(image.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Image compression failed." }
                    output.toByteArray()
                }
                quality -= 10
            } while (bytes.size > 256000 && quality >= 15)
            require(bytes.size <= 256000) { "Photo is too detailed. Choose a smaller photo." }
            bytes
        } finally { image.recycle() }
    }

    @RequiresApi(28)
    private fun decodeWithImageDecoder(resolver: android.content.ContentResolver, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height; val longest = maxOf(w, h)
            if (longest > MAX_EDGE) { val f = MAX_EDGE.toDouble() / longest; decoder.setTargetSize(maxOf(1, (w * f).toInt()), maxOf(1, (h * f).toInt())) }
        }

    private fun upright(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }
}
