package dev.rawrec.app.profiles

import android.os.Build
import org.json.JSONObject

data class DeviceProfile(
    val name: String,
    val rawPacking: String,
    val rowStrideQuirkBytes: Int?,
    val hiddenModes: Map<String, Int>,
    val rootOverrides: List<String>,
    val notes: String?
)

object DeviceProfiles {

    private const val GENERIC = """{
      "name": "generic",
      "rawPacking": "AUTODETECT",
      "rowStrideQuirkBytes": null,
      "hiddenModes": {},
      "rootOverrides": [],
      "notes": "runtime autodetection path"
    }"""

    private val bundled: Map<String, String> = mapOf(
        "generic" to GENERIC,
        "xiaomi_peridot" to """{
          "name": "xiaomi_peridot",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": null,
          "hiddenModes": {"50MP": 1, "4K60": 2},
          "rootOverrides": ["com.xiaomi.params.rawmode=1"],
          "notes": "POCO F6 / Snapdragon 8s Gen 3 (SM8635) IMX882 profile"
        }""",
        "xiaomi_sm8635" to """{
          "name": "xiaomi_sm8635",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": null,
          "hiddenModes": {"50MP": 1},
          "rootOverrides": [],
          "notes": "Snapdragon 8s Gen 3 Xiaomi devices"
        }""",
        "qualcomm_sm8650" to """{
          "name": "qualcomm_sm8650",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": null,
          "hiddenModes": {"8K30": 1, "4K120": 2},
          "rootOverrides": ["overrideForceUsecaseId=0"],
          "notes": "Snapdragon 8 Gen 3 reference"
        }""",
        "qualcomm_sm8550" to """{
          "name": "qualcomm_sm8550",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": null,
          "hiddenModes": {"4K60": 3},
          "rootOverrides": ["overrideForceSensorMode=3"],
          "notes": "Snapdragon 8 Gen 2 reference"
        }""",
        "sample-oem_sm8550" to """{
          "name": "sample-oem_sm8550",
          "rawPacking": "MIPI10_EXPANDED_MSB",
          "rowStrideQuirkBytes": 64,
          "hiddenModes": {"4K60": 3},
          "rootOverrides": ["overrideForceSensorMode=3"],
          "notes": "placeholder until validated on hardware"
        }""",
        "mediatek_dimensity" to """{
          "name": "mediatek_dimensity",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": 128,
          "hiddenModes": {},
          "rootOverrides": ["com.mediatek.streaming.rawmode=1"],
          "notes": "MediaTek Dimensity 128-byte row stride profile"
        }""",
        "samsung_exynos" to """{
          "name": "samsung_exynos",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": 64,
          "hiddenModes": {},
          "rootOverrides": [],
          "notes": "Samsung Exynos 64-byte row stride profile"
        }""",
        "google_tensor" to """{
          "name": "google_tensor",
          "rawPacking": "MIPI10_PACKED",
          "rowStrideQuirkBytes": null,
          "hiddenModes": {},
          "rootOverrides": [],
          "notes": "Google Tensor Camera HAL profile"
        }"""
    )

    fun current(): DeviceProfile {
        val matched = bundled.entries.firstOrNull {
            it.key != "generic" && keyMatches(it.key, manufacturer(), hardware())
        }
        return parse(matched?.value ?: bundled.getValue("generic"))
    }

    private fun manufacturer(): String = Build.MANUFACTURER?.uppercase().orEmpty()

    private fun hardware(): String = Build.HARDWARE?.uppercase().orEmpty()

    internal fun keyMatches(key: String, manufacturer: String, hardware: String): Boolean {
        val parts = key.split("_")
        val keyManufacturer = parts.getOrNull(0)?.uppercase() ?: return false
        val keySoc = parts.drop(1).joinToString("_")
        if (manufacturer.uppercase() != keyManufacturer) return false
        if (keySoc.isEmpty()) return true
        return hardware.uppercase().contains(keySoc.uppercase())
    }

    internal fun parse(json: String): DeviceProfile {
        val o = JSONObject(json)
        return DeviceProfile(
            name = o.getString("name"),
            rawPacking = o.optString("rawPacking", "AUTODETECT"),
            rowStrideQuirkBytes =
                if (o.isNull("rowStrideQuirkBytes")) null else o.getInt("rowStrideQuirkBytes"),
            hiddenModes = o.optJSONObject("hiddenModes")?.let { jo ->
                jo.keys().asSequence().associateWith { jo.getInt(it) }
            } ?: emptyMap(),
            rootOverrides = o.optJSONArray("rootOverrides")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList(),
            notes = if (o.isNull("notes")) null else o.optString("notes")
        )
    }
}
