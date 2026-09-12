package dev.rawrec.app.probe

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCatalogTest {

    @Test
    fun `computeFocalLength35mmEq calculates correct equivalent for standard sensor sizes`() {
        // Sony IMX882 / LYT-600 (1/1.95", approx 6.43mm x 4.82mm), focal length 4.8mm
        val eqWide = CameraCatalog.computeFocalLength35mmEq(
            minFocalLength = 4.8f,
            physicalWidthMm = 6.43f,
            physicalHeightMm = 4.82f
        )
        // diagonal = sqrt(6.43^2 + 4.82^2) = 8.036mm
        // 35mm eq = (43.3 / 8.036) * 4.8 = 25.86mm (~26mm wide lens)
        assertEquals(26.0f, eqWide, 0.5f)

        // Ultrawide (1/4" sensor, 3.2mm x 2.4mm = 4.0mm diagonal), focal length 1.65mm
        val eqUltraWide = CameraCatalog.computeFocalLength35mmEq(
            minFocalLength = 1.65f,
            physicalWidthMm = 3.2f,
            physicalHeightMm = 2.4f
        )
        // 35mm eq = (43.3 / 4.0) * 1.65 = 17.86mm (~16-18mm ultrawide)
        assertEquals(17.9f, eqUltraWide, 0.3f)

        // 3x Telephoto (1/2.5" sensor, 5.76mm x 4.29mm = 7.18mm diagonal), focal length 12.0mm
        val eqTele = CameraCatalog.computeFocalLength35mmEq(
            minFocalLength = 12.0f,
            physicalWidthMm = 5.76f,
            physicalHeightMm = 4.29f
        )
        // 35mm eq = (43.3 / 7.18) * 12.0 = 72.36mm (~75mm telephoto)
        assertEquals(72.4f, eqTele, 0.5f)
    }

    @Test
    fun `computeFocalLength35mmEq handles zero and degenerate dimensions gracefully`() {
        assertEquals(0f, CameraCatalog.computeFocalLength35mmEq(0f, 6.4f, 4.8f), 0.001f)
        assertEquals(0f, CameraCatalog.computeFocalLength35mmEq(4.8f, 0f, 0f), 0.001f)
        assertEquals(0f, CameraCatalog.computeFocalLength35mmEq(-2f, 6.4f, 4.8f), 0.001f)
    }

    @Test
    fun `classifyLensRole categorizes lenses accurately based on 35mm equivalent focal length`() {
        assertEquals(LensRole.FRONT, CameraCatalog.classifyLensRole(facing = "front", focal35mmEq = 22f))
        assertEquals(LensRole.ULTRAWIDE, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 14f))
        assertEquals(LensRole.ULTRAWIDE, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 23.9f))
        assertEquals(LensRole.WIDE, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 24f))
        assertEquals(LensRole.WIDE, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 28f))
        assertEquals(LensRole.WIDE, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 35f))
        assertEquals(LensRole.TELEPHOTO, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 50f))
        assertEquals(LensRole.TELEPHOTO, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 120f))
        assertEquals(LensRole.UNKNOWN, CameraCatalog.classifyLensRole(facing = "back", focal35mmEq = 0f))
    }

    @Test
    fun `CamInfo displayName generates professional camera and lens labels`() {
        val standaloneWide = CamInfo(
            id = "0",
            facing = "back",
            hardwareLevel = "L3",
            hasRaw = true,
            rawSizes = listOf(Size(4096, 3072)),
            previewSizes = listOf(Size(960, 720)),
            sensorOrientation = 90,
            whiteLevel = 1023,
            cfaPattern = 3,
            exposureRangeNs = null,
            isoRange = null,
            focalLength35mmEq = 24.3f,
            lensRole = LensRole.WIDE
        )
        assertEquals("cam0 · 24mm Wide", standaloneWide.displayName)

        val physicalUltrawide = CamInfo(
            id = "0",
            physicalId = "2",
            logicalParentId = "0",
            facing = "back",
            hardwareLevel = "FULL",
            hasRaw = true,
            rawSizes = listOf(Size(3264, 2448)),
            previewSizes = listOf(Size(960, 720)),
            sensorOrientation = 90,
            whiteLevel = 1023,
            cfaPattern = 0,
            exposureRangeNs = null,
            isoRange = null,
            focalLength35mmEq = 14.8f,
            lensRole = LensRole.ULTRAWIDE
        )
        assertEquals("cam0:2 · 15mm Ultrawide", physicalUltrawide.displayName)

        val frontSelfie = CamInfo(
            id = "1",
            facing = "front",
            hardwareLevel = "FULL",
            hasRaw = false,
            rawSizes = emptyList(),
            previewSizes = listOf(Size(1920, 1080)),
            sensorOrientation = 270,
            whiteLevel = 1023,
            cfaPattern = 0,
            exposureRangeNs = null,
            isoRange = null,
            focalLength35mmEq = 22.1f,
            lensRole = LensRole.FRONT
        )
        assertEquals("cam1 · 22mm Front", frontSelfie.displayName)
    }

    @Test
    fun `CANDIDATE_AUX_IDS contains standard OEM auxiliary camera ports`() {
        val auxList = CameraCatalog.CANDIDATE_AUX_IDS
        assertTrue(auxList.contains("2"))
        assertTrue(auxList.contains("3"))
        assertTrue(auxList.contains("20"))
        assertTrue(auxList.contains("21"))
        assertTrue(auxList.contains("60"))
    }

    @Test
    fun `filterPhysicalSensors strips duplicate virtual ports and external streams keeping only physical sensors`() {
        val cam0 = CamInfo(
            id = "0", facing = "back", hardwareLevel = "L3", hasRaw = true,
            rawSizes = listOf(Size(4096, 3072)), previewSizes = listOf(Size(960, 720)),
            sensorOrientation = 90, whiteLevel = 1023, cfaPattern = 3,
            exposureRangeNs = null, isoRange = null,
            focalLengthMm = 4.8f, focalLength35mmEq = 26f, lensRole = LensRole.WIDE
        )
        val cam1 = CamInfo(
            id = "1", facing = "front", hardwareLevel = "L3", hasRaw = true,
            rawSizes = emptyList(), previewSizes = listOf(Size(1920, 1080)),
            sensorOrientation = 270, whiteLevel = 1023, cfaPattern = 0,
            exposureRangeNs = null, isoRange = null,
            focalLengthMm = 2.4f, focalLength35mmEq = 21f, lensRole = LensRole.FRONT
        )
        val cam2 = CamInfo(
            id = "2", facing = "back", hardwareLevel = "L3", hasRaw = true,
            rawSizes = listOf(Size(3264, 2448)), previewSizes = listOf(Size(960, 720)),
            sensorOrientation = 90, whiteLevel = 1023, cfaPattern = 0,
            exposureRangeNs = null, isoRange = null,
            focalLengthMm = 1.65f, focalLength35mmEq = 16f, lensRole = LensRole.ULTRAWIDE
        )
        // cam3: virtual duplicate of cam0 (same rear facing and same 26mm focal length)
        val cam3 = cam0.copy(id = "3")
        // cam4: virtual duplicate of cam0 (same rear facing and same 26mm focal length)
        val cam4 = cam0.copy(id = "4")
        // cam5: virtual duplicate of cam1 (same front facing and same 21mm focal length)
        val cam5 = cam1.copy(id = "5")
        // cam6: external virtual port
        val cam6 = cam0.copy(id = "6", facing = "ext", focalLength35mmEq = 0f, lensRole = LensRole.UNKNOWN)

        val allDiscovered = listOf(cam0, cam1, cam2, cam3, cam4, cam5, cam6)
        val filtered = CameraCatalog.filterPhysicalSensors(allDiscovered)

        // Must contain only the 3 real physical sensors: Main Wide (cam0), Front Selfie (cam1), Ultrawide (cam2)
        assertEquals(3, filtered.size)
        assertEquals("0", filtered[0].id)
        assertEquals(LensRole.WIDE, filtered[0].lensRole)
        assertEquals("1", filtered[1].id)
        assertEquals(LensRole.FRONT, filtered[1].lensRole)
        assertEquals("2", filtered[2].id)
        assertEquals(LensRole.ULTRAWIDE, filtered[2].lensRole)
    }
}
