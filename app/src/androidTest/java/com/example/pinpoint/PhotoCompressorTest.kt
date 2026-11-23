package com.example.pinpoint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.data.PhotoCompressor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/** Generated media only; local files, no network. */
@RunWith(AndroidJUnit4::class)
class PhotoCompressorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()
    @After fun cleanup() { PhotoCompressor.forceFallbackForTesting(false); files.forEach { it.delete() } }

    private fun jpeg(width: Int, height: Int, orientation: Int? = null, gps: Boolean = false, noise: Boolean = false): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (noise) { val random = Random(7); val row = IntArray(width); for (y in 0 until height) { for (x in 0 until width) row[x] = random.nextInt() or (0xFF shl 24); bitmap.setPixels(row, 0, width, 0, y, width, 1) } }
        else bitmap.eraseColor(android.graphics.Color.rgb(200, 40, 40))
        val file = File(context.cacheDir, "pc-${System.nanoTime()}.jpg").also { files += it }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
        if (orientation != null || gps) ExifInterface(file.absolutePath).apply {
            orientation?.let { setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
            if (gps) { setAttribute(ExifInterface.TAG_GPS_LATITUDE, "13/1,46/1,52/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,4/1,24/1"); setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E") }
            saveAttributes()
        }
        return Uri.fromFile(file)
    }
    private fun compress(uri: Uri) = runBlocking { PhotoCompressor.jpeg(context, uri) }
    private fun size(bytes: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }.let { it.outWidth to it.outHeight }

    @Test fun exifRotatedPhotoIsStoredUpright() {
        // A 400x200 sensor image tagged "rotate 90" is displayed portrait (200x400) by every viewer.
        val out = compress(jpeg(400, 200, orientation = ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals("EXIF orientation 6 must be applied to the pixels", 200 to 400, size(out))
    }
    @Test fun exifRotate180AndMirroredAreApplied() {
        assertEquals(400 to 200, size(compress(jpeg(400, 200, orientation = ExifInterface.ORIENTATION_ROTATE_180))))
        assertEquals(200 to 400, size(compress(jpeg(400, 200, orientation = ExifInterface.ORIENTATION_TRANSPOSE))))
    }
    @Test fun gpsMetadataIsNotCopiedIntoTheUploadedPhoto() {
        val source = jpeg(300, 200, gps = true)
        assertNotNull("fixture must contain GPS", ExifInterface(source.path!!).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        val out = compress(source)
        val result = File(context.cacheDir, "pc-out.jpg").also { files += it; it.writeBytes(out) }
        assertNull("uploaded photo must not carry GPS", ExifInterface(result.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }
    @Test fun largeDimensionsAreBoundedAndSmall() {
        val out = compress(jpeg(8000, 6000))
        val (w, h) = size(out)
        assertTrue("$w x $h exceeds 1280", maxOf(w, h) <= 1280); assertTrue(out.size <= 256000)
    }
    @Test fun veryDetailedImageEitherFitsOrIsRejectedWithAClearMessage() {
        val uri = jpeg(3000, 2000, noise = true)
        try { assertTrue(compress(uri).size <= 256000) }
        catch (error: IllegalArgumentException) { assertEquals("Photo is too detailed. Choose a smaller photo.", error.message) }
    }
    @Test fun corruptContentIsRejectedWithAClearMessage() {
        val file = File(context.cacheDir, "pc-corrupt.jpg").also { files += it; it.writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val error = runCatching { compress(Uri.fromFile(file)) }.exceptionOrNull()
        assertTrue("got $error", error is IllegalArgumentException); assertEquals("Cannot read the selected image.", error!!.message)
    }
    @Test fun nonImageTextIsRejected() {
        val file = File(context.cacheDir, "pc-text.jpg").also { files += it; it.writeText("not an image at all") }
        assertTrue(runCatching { compress(Uri.fromFile(file)) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun vanishedSourceFailsWithoutProducingBytes() {
        val uri = jpeg(100, 100); File(uri.path!!).delete()
        assertNotNull(runCatching { compress(uri) }.exceptionOrNull())
    }

    private fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat, name: String): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(3); for (y in 0 until height step 7) for (x in 0 until width step 7) bitmap.setPixel(x, y, random.nextInt() or (0xFF shl 24))
        val file = File(context.cacheDir, name).also { files += it }
        file.outputStream().use { assertTrue(bitmap.compress(format, 90, it)) }; bitmap.recycle()
        return Uri.fromFile(file)
    }
    @Test fun otherCommonImageFormatsAreAcceptedAndBounded() {
        for ((format, name) in listOf(Bitmap.CompressFormat.PNG to "pc-x.png", Bitmap.CompressFormat.WEBP_LOSSY to "pc-x.webp")) {
            val out = compress(encoded(3000, 2000, format, name)); val (w, h) = size(out)
            assertTrue("$name -> ${w}x$h", maxOf(w, h) <= 1280 && out.size <= 256000)
        }
    }
    @Test fun aTwelveMegapixelPhotoStyleJpegIsAcceptedAndBounded() {
        val out = compress(encoded(4000, 3000, Bitmap.CompressFormat.JPEG, "pc-12mp.jpg")); val (w, h) = size(out)
        assertTrue("${w}x$h", maxOf(w, h) <= 1280 && out.size <= 256000)
    }
    @Test fun aHugeJpegDoesNotExhaustMemory() {
        val out = compress(encoded(12000, 9000, Bitmap.CompressFormat.JPEG, "pc-108mp.jpg")); assertTrue(maxOf(size(out).first, size(out).second) <= 1280)
    }

    // ---- ImageDecoder fallback (used when BitmapFactory returns nothing for a real photo); forced here so it is deterministic.
    private fun viaFallback(uri: Uri): ByteArray { PhotoCompressor.forceFallbackForTesting(true); return compress(uri) }

    @Test fun fallbackAppliesExifOrientationOnceAndStripsGps() {
        val out = viaFallback(jpeg(400, 200, orientation = ExifInterface.ORIENTATION_ROTATE_90, gps = true))
        assertEquals("ImageDecoder already orients the image: it must not be rotated a second time", 200 to 400, size(out))
        val result = File(context.cacheDir, "pc-fb-out.jpg").also { files += it; it.writeBytes(out) }
        assertNull(ExifInterface(result.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }
    @Test fun fallbackBoundsLargeAndHugeImages() {
        assertTrue(size(viaFallback(jpeg(8000, 6000))).let { maxOf(it.first, it.second) <= 1280 })
        assertTrue(size(viaFallback(encoded(12000, 9000, Bitmap.CompressFormat.JPEG, "pc-fb-108mp.jpg"))).let { maxOf(it.first, it.second) <= 1280 })
    }
    @Test fun fallbackAcceptsPngAndWebp() {
        for ((format, name) in listOf(Bitmap.CompressFormat.PNG to "pc-fb.png", Bitmap.CompressFormat.WEBP_LOSSY to "pc-fb.webp")) {
            val (w, h) = size(viaFallback(encoded(3000, 2000, format, name))); assertTrue("$name ${w}x$h", maxOf(w, h) <= 1280)
        }
    }
    @Test fun fallbackRejectsCorruptEmptyAndNonImageInputWithTheExistingMessage() {
        val corrupt = File(context.cacheDir, "pc-fb-corrupt.jpg").also { files += it; it.writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val empty = File(context.cacheDir, "pc-fb-empty.jpg").also { files += it; it.writeBytes(ByteArray(0)) }
        val text = File(context.cacheDir, "pc-fb-text.jpg").also { files += it; it.writeText("not an image") }
        for (f in listOf(corrupt, empty, text)) {
            val error = runCatching { viaFallback(Uri.fromFile(f)) }.exceptionOrNull()
            assertTrue("${f.name}: $error", error is IllegalArgumentException); assertEquals("Cannot read the selected image.", error!!.message)
        }
    }
    @Test fun fallbackHandlesATruncatedJpegWithoutCrashing() {
        val full = File(jpeg(3000, 2000).path!!).readBytes()
        val truncated = File(context.cacheDir, "pc-fb-trunc.jpg").also { files += it; it.writeBytes(full.copyOf(full.size / 2)) }
        val result = runCatching { viaFallback(Uri.fromFile(truncated)) }
        // A partly readable photo may decode (bounded) or be refused with the clear message; it must never crash or return oversized bytes.
        result.onSuccess { assertTrue(maxOf(size(it).first, size(it).second) <= 1280 && it.size <= 256000) }
        result.onFailure { assertTrue("$it", it is IllegalArgumentException && it.message == "Cannot read the selected image.") }
    }
    @Test fun fallbackKeepsAVanishedSourceDistinctFromACorruptOne() {
        val uri = jpeg(100, 100); File(uri.path!!).delete()
        val error = runCatching { viaFallback(uri) }.exceptionOrNull()
        assertTrue("a vanished source must fail without producing bytes: $error", error != null && error !is IllegalArgumentException)
    }
}
