package dev.rawrec.app.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {

    private val sampleJson = """
        {
          "name": "sample-oem_sm8550",
          "rawPacking": "MIPI10_EXPANDED_MSB",
          "rowStrideQuirkBytes": 64,
          "hiddenModes": {"4K60": 3},
          "rootOverrides": ["overrideForceSensorMode=3"],
          "notes": "placeholder until validated on hardware"
        }
    """.trimIndent()

    @Test
    fun `keyMatches returns true when manufacturer and soc match`() {
        assertTrue(
            DeviceProfiles.keyMatches(
                key = "oem_sm8550",
                manufacturer = "OEM",
                hardware = "SM8550"
            )
        )
    }

    @Test
    fun `keyMatches is case insensitive`() {
        assertTrue(
            DeviceProfiles.keyMatches("OEM_SM8550", "oEm", "sm8550-ACPI")
        )
    }

    @Test
    fun `keyMatches uses substring for soc part`() {
        assertTrue(
            DeviceProfiles.keyMatches("acme_pineapple", "ACME", "qcom-pineapple-v2")
        )
    }

    @Test
    fun `keyMatches fails on manufacturer mismatch`() {
        assertFalse(
            DeviceProfiles.keyMatches("oem_sm8550", "other", "SM8550")
        )
    }

    @Test
    fun `keyMatches fails when soc missing from hardware string`() {
        assertFalse(
            DeviceProfiles.keyMatches("oem_sm8550", "oem", "kalama-generic")
        )
    }

    @Test
    fun `keyMatches with bare manufacturer key matches any hardware`() {
        assertTrue(DeviceProfiles.keyMatches("oem", "OEM", "anything"))
        assertFalse(DeviceProfiles.keyMatches("oem", "OTHER", "anything"))
    }

    @Test
    fun `parse extracts all fields`() {
        val p = DeviceProfiles.parse(sampleJson)
        assertEquals("sample-oem_sm8550", p.name)
        assertEquals("MIPI10_EXPANDED_MSB", p.rawPacking)
        assertEquals(64, p.rowStrideQuirkBytes)
        assertEquals(mapOf("4K60" to 3), p.hiddenModes)
        assertEquals(listOf("overrideForceSensorMode=3"), p.rootOverrides)
        assertEquals("placeholder until validated on hardware", p.notes)
    }

    @Test
    fun `parse generic profile yields defaults`() {
        val generic = """
            {"name":"generic","rawPacking":"AUTODETECT","rowStrideQuirkBytes":null,
             "hiddenModes":{},"rootOverrides":[],"notes":null}
        """.trimIndent()
        val p = DeviceProfiles.parse(generic)
        assertEquals("generic", p.name)
        assertEquals("AUTODETECT", p.rawPacking)
        assertNull(p.rowStrideQuirkBytes)
        assertTrue(p.hiddenModes.isEmpty())
        assertTrue(p.rootOverrides.isEmpty())
        assertNull(p.notes)
    }

    @Test
    fun `parse tolerates omitted optional keys`() {
        val p = DeviceProfiles.parse("""{"name":"minimal"}""")
        assertEquals("minimal", p.name)
        assertEquals("AUTODETECT", p.rawPacking)
        assertNull(p.rowStrideQuirkBytes)
        assertTrue(p.hiddenModes.isEmpty())
        assertTrue(p.rootOverrides.isEmpty())
        assertNull(p.notes)
    }

    @Test
    fun `current falls back to generic when no device metadata available`() {
        val p = DeviceProfiles.current()
        assertEquals("generic", p.name)
        assertEquals("AUTODETECT", p.rawPacking)
    }
}
