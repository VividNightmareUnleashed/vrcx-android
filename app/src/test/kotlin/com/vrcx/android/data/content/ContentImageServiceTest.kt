package com.vrcx.android.data.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vrcx.android.data.util.MAX_UPLOAD_SIZE_BYTES
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ContentImageServiceTest {
    @Test
    fun `upload dimensions preserve aspect ratio within two thousand pixels`() {
        assertEquals(2_000 to 1_000, GalleryImageProcessor.fitDimensions(4_000, 2_000))
        assertEquals(1_000 to 2_000, GalleryImageProcessor.fitDimensions(2_000, 4_000))
        assertEquals(800 to 600, GalleryImageProcessor.fitDimensions(800, 600))
    }

    @Test
    fun `upload sample size lands the decode at or below the max dimension`() {
        assertEquals(1, GalleryImageProcessor.calculateSampleSize(2_000, 2_000))
        assertEquals(4, GalleryImageProcessor.calculateSampleSize(8_000, 4_000))
        assertEquals(8, GalleryImageProcessor.calculateSampleSize(16_000, 8_000))

        listOf(2_001 to 1_000, 8_000 to 4_000, 16_000 to 8_000, 5_000 to 12_000).forEach { (w, h) ->
            val sample = GalleryImageProcessor.calculateSampleSize(w, h)
            assertTrue("$w x $h sampled by $sample", w / sample <= 2_000 && h / sample <= 2_000)
        }
    }

    @Test
    fun `an oversized metadata size is rejected before any stream is opened`() {
        var opened = false
        val result = readBoundedUpload(
            metadataSize = 40_000_000L,
            mimeType = "image/png",
            fileName = "big.png",
        ) {
            opened = true
            ByteArrayInputStream(ByteArray(0))
        }

        assertEquals(GalleryPreparationResult.TooLarge, result)
        assertFalse("the file was read despite its declared size", opened)
    }

    @Test
    fun `the read is bounded even when the metadata size lies`() {
        val result = readBoundedUpload(
            metadataSize = null,
            mimeType = "image/png",
            fileName = "lies.png",
        ) {
            ByteArrayInputStream(ByteArray(MAX_UPLOAD_SIZE_BYTES + 1))
        }

        assertEquals(GalleryPreparationResult.TooLarge, result)
    }

    @Test
    fun `a readable image within the ceiling carries its name and type through`() {
        val result = readBoundedUpload(
            metadataSize = 3L,
            mimeType = "image/jpeg",
            fileName = "shot.jpg",
        ) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        val upload = (result as GalleryPreparationResult.Success).upload
        assertEquals("shot.jpg", upload.fileName)
        assertEquals("image/jpeg", upload.mimeType)
        assertEquals(3, upload.bytes.size)
    }

    @Test
    fun `an unopenable image is unreadable rather than too large`() {
        val result = readBoundedUpload(
            metadataSize = null,
            mimeType = "image/png",
            fileName = "gone.png",
        ) { null }

        assertEquals(GalleryPreparationResult.Unreadable, result)
    }

    @Test
    fun `decoded MIME type wins when provider metadata is missing or incorrect`() {
        assertEquals("image/jpeg", GalleryImageProcessor.resolveMimeType(null, "image/jpeg", 20, 10))
        assertEquals("image/webp", GalleryImageProcessor.resolveMimeType("image/png", "image/webp", 20, 10))
        assertEquals(
            "image/png",
            GalleryImageProcessor.resolveMimeType("IMAGE/PNG; charset=binary", "image/png", 20, 10),
        )
    }

    @Test
    fun `provider image type cannot make arbitrary bytes a valid upload`() {
        val fakeImage = "this is not an image".toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        BitmapFactory.decodeStream(ByteArrayInputStream(fakeImage), null, bounds)

        assertNull(
            GalleryImageProcessor.resolveMimeType(
                declared = "image/png",
                detected = bounds.outMimeType,
                width = bounds.outWidth,
                height = bounds.outHeight,
            ),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    @Config(sdk = [29])
    fun `webp uses the compatibility format before Android 11`() {
        assertEquals(Bitmap.CompressFormat.WEBP, GalleryImageProcessor.compressFormat("image/webp"))
    }

    @Test
    @Config(sdk = [30])
    fun `webp uses the explicit lossy format from Android 11`() {
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, GalleryImageProcessor.compressFormat("image/webp"))
    }
}
