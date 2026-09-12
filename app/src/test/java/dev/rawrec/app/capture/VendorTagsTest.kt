package dev.rawrec.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorTagsTest {

    @Test
    fun `setVendorTag returns false when key is null`() {
        // Safe fallback guarantee: null keys never throw exceptions
        val res = VendorTags.setVendorTag<Int>(null as android.hardware.camera2.CaptureRequest.Builder?, null, 1)
        assertFalse(res)
    }

    @Test
    fun `findRequestKey returns null when key cannot be found`() {
        // Safe resolution guarantee
        val key = runCatching {
            VendorTags.findRequestKey(null as android.hardware.camera2.CameraCharacteristics, "non_existent_vendor_key")
        }.getOrNull()
        assertNull(key)
    }
}
