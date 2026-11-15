package com.openminis.app.device

import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.1 completeness: every device tool declares [com.openminis.app.data.model.ToolAnnotations],
 * and the destructive / read-only sets stay in lockstep with the
 * DevicePermission risk classes. A new tool added to the registry without
 * annotations — or with hints contradicting its risk class — fails here
 * instead of silently shipping unknown hints to the approval layer.
 *
 * Mapping rules this pins:
 *  - DANGEROUS risk ("acts on your behalf") implies destructiveHint=true
 *  - deletion-class tools (notification_dismiss) carry destructiveHint=true
 *  - READ_ONLY risk implies readOnlyHint=true AND destructiveHint=false
 *  - no destructiveHint on anything the permission map calls READ_ONLY
 */
class DeviceToolsAnnotationsTest {

    private val definitions = DeviceTools.definitions()

    @Test
    fun everyDeviceToolDeclaresAnnotations() {
        val missing = definitions.filter { it.annotations == null }.map { it.name }
        assertTrue("device tools without annotations: $missing", missing.isEmpty())
    }

    @Test
    fun deviceToolSetIsStillTheThirtyToolBatchA() {
        // Guards the lockstep tests below against silently shrinking scope.
        assertEquals(30, definitions.size)
    }

    @Test
    fun dangerousRiskImpliesDestructiveHint() {
        val offenders = DevicePermission.REQUIREMENTS
            .filter { it.value.risk == DeviceToolRisk.DANGEROUS }
            .keys
            .filter { name ->
                definitions.first { d -> d.name == name }.annotations?.destructiveHint != true
            }
        assertTrue("DANGEROUS tools without destructiveHint=true: $offenders", offenders.isEmpty())
    }

    @Test
    fun readOnlyRiskImpliesReadOnlyHintAndNoDestructive() {
        val offenders = definitions.filter { def ->
            val risk = DevicePermission.requirement(def.name)!!.risk
            risk == DeviceToolRisk.READ_ONLY &&
                (def.annotations?.readOnlyHint != true || def.annotations?.destructiveHint != false)
        }.map { it.name }
        assertTrue("READ_ONLY tools with wrong hints: $offenders", offenders.isEmpty())
    }

    @Test
    fun destructiveHintNeverOnReadOnlyRisk() {
        val offenders = definitions.filter { def ->
            def.isDestructive() && DevicePermission.requirement(def.name)!!.risk == DeviceToolRisk.READ_ONLY
        }.map { it.name }
        assertTrue("destructive hints on READ_ONLY tools: $offenders", offenders.isEmpty())
    }

    @Test
    fun destructiveSetIsExactlyTheDeletionAndOnBehalfTools() {
        val destructive = definitions.filter { it.isDestructive() }.map { it.name }.toSet()
        assertEquals(
            "destructive set drifted: app_send_intent/notification_reply/sms_send act on the " +
                "user's behalf (DANGEROUS) and notification_dismiss deletes data",
            setOf("app_send_intent", "notification_dismiss", "notification_reply", "sms_send"),
            destructive,
        )
    }

    @Test
    fun shellAndSuExecCarryDestructiveHints() {
        // Outside DeviceTools (they live in the AgentTools registry) but the
        // task pins them to the same destructive shape.
        val all = AgentTools.makeAgentTools()
        for (name in listOf("shell_execute", "su_exec")) {
            val def = all.firstOrNull { it.name == name }
            assertNotNull("$name missing from the AgentTools registry", def)
            assertTrue("$name must carry destructiveHint=true", def!!.isDestructive())
        }
    }

    @Test
    fun exposureDefaultsToDirectNullOnDeviceTools() {
        // Device tools are all plain "direct" (null); Phase D exposure values
        // belong to codemode/deferred loading and must be explicit when they come.
        val nonDefault = definitions.filter { it.exposure != null }.map { it.name }
        assertTrue("device tools with non-null exposure: $nonDefault", nonDefault.isEmpty())
    }
}
