package com.openminis.app.device

import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema-level guarantees for the device tool set: the permission map and the
 * definition list stay in lockstep, every required parameter exists and is
 * described, and every description carries its risk label at the tail.
 */
class DeviceToolsSchemaTest {

    private val definitions: List<AgentToolDefinition> = DeviceTools.definitions()

    @Test
    fun definitionsAndPermissionMapAreInLockstep() {
        assertTrue("expected the full batch-A tool set, got ${definitions.size}", definitions.size == 30)
        for (def in definitions) {
            assertNotNull("no DevicePermission entry for ${def.name}", DevicePermission.requirement(def.name))
            assertTrue("handles() must accept ${def.name}", DeviceTools.handles(def.name))
        }
        assertEquals(
            "REQUIREMENTS has entries without a definition",
            definitions.map { it.name }.sorted(),
            DevicePermission.REQUIREMENTS.keys.sorted(),
        )
        assertFalse(DeviceTools.handles("not_a_device_tool"))
    }

    @Test
    fun definitionsAreUniqueAndWellNamed() {
        assertEquals(definitions.size, definitions.map { it.name }.toSet().size)
        for (def in definitions) {
            assertTrue("tool names must be snake_case: ${def.name}", def.name.matches(Regex("[a-z][a-z0-9_]+")))
            assertTrue("descriptions must be non-empty: ${def.name}", def.description.isNotBlank())
        }
    }

    @Test
    fun requiredParametersExistAndAreDescribed() {
        val validTypes = setOf("string", "integer", "boolean", "array", "object")
        for (def in definitions) {
            for (required in def.required) {
                val param = def.parameters[required]
                assertNotNull("${def.name}: required '$required' missing from parameters", param)
                assertTrue(
                    "${def.name}: required '$required' has a blank description",
                    param!!.description.isNotBlank(),
                )
            }
            for ((key, param) in def.parameters) {
                assertTrue(
                    "${def.name}: parameter '$key' has type '${param.type}'",
                    param.type in validTypes,
                )
                assertTrue(
                    "${def.name}: parameter '$key' has a blank description",
                    param.description.isNotBlank(),
                )
                if (param.type == "array") {
                    assertNotNull("${def.name}: array parameter '$key' needs an items schema", param.items)
                    assertTrue("${def.name}: array item of '$key' needs a description", param.items!!.description.isNotBlank())
                }
                if (param.enumValues != null) {
                    assertTrue("${def.name}: enum parameter '$key' must list values", param.enumValues!!.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun descriptionsEndWithTheirRiskLabel() {
        val expectedPrefix = mapOf(
            DeviceToolRisk.READ_ONLY to "Read-only.",
            DeviceToolRisk.WRITE to "Writes device state.",
            DeviceToolRisk.SENSITIVE to "Sensitive:",
            DeviceToolRisk.DANGEROUS to "Dangerous:",
        )
        for (def in definitions) {
            val risk = DevicePermission.requirement(def.name)!!.risk
            val lastSentence = def.description.substringAfterLast(". ")
            assertTrue(
                "${def.name}: description must end with its risk label (got '...$lastSentence')",
                lastSentence.startsWith(expectedPrefix[risk]!!),
            )
        }
    }

    @Test
    fun riskyGroupsCarryTheRightRisk() {
        // Spot-check the mapping the report and the UI rely on.
        assertEquals(DeviceToolRisk.READ_ONLY, DevicePermission.requirement("device_status")!!.risk)
        assertEquals(DeviceToolRisk.WRITE, DevicePermission.requirement("app_launch")!!.risk)
        assertEquals(DeviceToolRisk.SENSITIVE, DevicePermission.requirement("sms_read")!!.risk)
        assertEquals(DeviceToolRisk.DANGEROUS, DevicePermission.requirement("sms_send")!!.risk)
        assertEquals(DeviceToolRisk.DANGEROUS, DevicePermission.requirement("notification_reply")!!.risk)
        assertEquals(DeviceToolRisk.SENSITIVE, DevicePermission.requirement("location_get")!!.risk)
        assertEquals(
            listOf(DevicePermission.SPECIAL_NOTIFICATION_POLICY),
            DevicePermission.requirement("dnd_set")!!.special,
        )
        assertEquals(
            listOf(DevicePermission.SPECIAL_NOTIFICATION_LISTENER),
            DevicePermission.requirement("notification_reply")!!.special,
        )
        assertEquals(
            listOf(DevicePermission.SPECIAL_WRITE_SETTINGS),
            DevicePermission.requirement("brightness_set")!!.special,
        )
        // sound_mode's DND need is conditional (silent transitions) and therefore checked inline.
        assertTrue(DevicePermission.requirement("sound_mode")!!.special.isEmpty())
    }

    @Test
    fun settingKeyTableCoversCommonKeysAndDrivesTheDescription() {
        val global = SystemTools.COMMON_KEYS.getValue("global")
        val system = SystemTools.COMMON_KEYS.getValue("system")
        val secure = SystemTools.COMMON_KEYS.getValue("secure")
        assertTrue("airplane_mode_on" in global)
        assertTrue("wifi_on" in global)
        assertTrue("screen_off_timeout" in system)
        assertTrue("screen_brightness" in system)
        assertTrue("default_input_method" in secure)
        val settingGet = definitions.first { it.name == "setting_get" }
        for ((ns, keys) in SystemTools.COMMON_KEYS) {
            for (key in keys) {
                assertTrue(
                    "setting_get description must document $ns/$key",
                    settingGet.description.contains(key),
                )
            }
        }
        // Keys are sanitized to [A-Za-z0-9.]: no shell or SQL metacharacters survive.
        assertEquals("screen_off_timeout", SystemTools.sanitizeKey("screen_off_timeout"))
        assertEquals("wifi.on", SystemTools.sanitizeKey("wifi.on"))
        assertEquals("abdroptable", SystemTools.sanitizeKey("a;b drop table--"))
    }

    @Test
    fun shizukuToolsAreTheThreeConnectivityTogglesPlusSettingGet() {
        // These tools must degrade gracefully to a system panel when Shizuku is absent;
        // the property lives in their behaviour, the map here keeps the set honest.
        val shizukuCapable = setOf("wifi_toggle", "bluetooth_toggle", "mobile_data_toggle")
        for (name in shizukuCapable) {
            assertTrue(DeviceTools.handles(name))
            assertEquals(DeviceToolRisk.WRITE, DevicePermission.requirement(name)!!.risk)
        }
    }
}
