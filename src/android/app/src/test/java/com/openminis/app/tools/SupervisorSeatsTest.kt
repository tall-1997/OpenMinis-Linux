package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

class SupervisorSeatsTest {

    private fun tool(name: String) = AgentToolDefinition(
        name = name,
        description = "",
        parameters = emptyMap(),
    )

    private fun base() = listOf(
        tool("file_write"),
        tool("file_edit"),
        tool("shell_execute"),
        tool("file_read"),
        tool("grep"),
        tool("glob"),
        tool("code_graph"),
        tool("spawn_agent"),
        tool("check_agent"),
        tool("cronjob"),
        tool("ask_user_question"),
        tool("browser_use"),
    )

    @Test
    fun workerTools_keepsWriteAndShell_dropsSpawnAndControlTools() {
        val names = SupervisorSeats.workerTools(base()).map { it.name }
        assertEquals(
            listOf(
                "file_write",
                "file_edit",
                "shell_execute",
                "file_read",
                "grep",
                "glob",
                "code_graph",
                "browser_use",
            ),
            names,
        )
    }

    @Test
    fun supervisorTools_isReconSurface_dropsFileMutationAndControlTools() {
        val names = SupervisorSeats.supervisorTools(base()).map { it.name }
        assertEquals(
            listOf(
                "shell_execute",
                "file_read",
                "grep",
                "glob",
                "code_graph",
                "browser_use",
            ),
            names,
        )
    }

    @Test
    fun both_surfaces_preserveBaseOrder() {
        val base = listOf(tool("zz_last"), tool("aa_first"), tool("file_read"))
        assertEquals(
            listOf("zz_last", "aa_first", "file_read"),
            SupervisorSeats.workerTools(base).map { it.name },
        )
        // Recon tools survive in base order (glob before grep proves no
        // re-sort); unknown tools are not recon tools and are dropped.
        val recon = listOf(tool("glob"), tool("file_read"), tool("grep"))
        assertEquals(
            listOf("glob", "file_read", "grep"),
            SupervisorSeats.supervisorTools(recon).map { it.name },
        )
    }

    @Test
    fun both_surfaces_emptyBaseYieldsEmpty() {
        assertEquals(0, SupervisorSeats.workerTools(emptyList()).size)
        assertEquals(0, SupervisorSeats.supervisorTools(emptyList()).size)
    }
}
