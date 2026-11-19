 package com.openminis.app.data
 
 import com.openminis.app.agent.HarnessConfig
 import org.junit.After
 import org.junit.Assert.assertEquals
 import org.junit.Assert.assertFalse
 import org.junit.Assert.assertNull
 import org.junit.Assert.assertTrue
 import org.junit.Before
 import org.junit.Test
 import java.io.File
 import java.nio.file.Files
 
 /**
  * [T-harness-config-store] `6.2`: a per-session override must survive a
  * restart and must not leak into the next session.
  *
  * The acceptance item is concrete —32K /128K /200K models each use their
  * own thresholds — so the tests use a real temp directory and assert the round
  * trip, not the object's shape.
  */
 class HarnessConfigStoreTest {
 
    private lateinit var dir: File
    
    @Before
    fun setUp() {
        dir = Files.createTempDirectory("harness-config").toFile()
        HarnessConfig.resetForTest()
    }
    
    @After
    fun tearDown() {
        HarnessConfig.resetForTest()
        dir.deleteRecursively()
    }
    
    @Test
    fun anOverrideRoundTrips() {
        val custom = HarnessConfig(postAnchorBudgetTokens =40_000, noticeReserveTokens =800)
        assertTrue(HarnessConfigStore.save(dir, "s1", custom))
        assertEquals(custom, HarnessConfigStore.load(dir, "s1"))
    }
    
    @Test
    fun sessionsDoNotShareAnOverride() {
        val small = HarnessConfig(postAnchorBudgetTokens =12_000, noticeReserveTokens =400)
        HarnessConfigStore.save(dir, "window-32k", small)
        assertNull(HarnessConfigStore.load(dir, "window-200k"))
        assertEquals(small, HarnessConfigStore.load(dir, "window-32k"))
    }
    
    @Test
    fun installAppliesTheStoredValueAndResetsWithoutOne() {
        val custom = HarnessConfig(noticeReserveTokens =500)
        HarnessConfigStore.save(dir, "a", custom)
        HarnessConfigStore.install(dir, "a")
        assertEquals(custom, HarnessConfig.current())
        // A session with no file must not inherit the previous session's numbers.
        HarnessConfigStore.install(dir, "b")
        assertEquals(HarnessConfig.DEFAULT, HarnessConfig.current())
    }
    
    @Test
    fun anInvalidOverrideIsNeverPersisted() {
        // Validation lives in one place; the store must not be a way around it.
        assertFalse(HarnessConfigStore.save(dir, "s", HarnessConfig(postAnchorBudgetTokens =0)))
        assertNull(HarnessConfigStore.load(dir, "s"))
    }
    
    @Test
    fun aCorruptFileFallsBackToTheDefaults() {
        HarnessConfigStore.file(dir, "s").parentFile?.mkdirs()
        HarnessConfigStore.file(dir, "s").writeText("not json")
        assertNull(HarnessConfigStore.load(dir, "s"))
        HarnessConfigStore.install(dir, "s")
        assertEquals(HarnessConfig.DEFAULT, HarnessConfig.current())
    }
    
    @Test
    fun aFileWrittenByAnOlderBuildKeepsTheMissingFieldsAtTheirDefaults() {
        // Forward compatibility: a file with fewer keys must not reset the whole
        // override, and must not read the absent fields as zero.
        val target = HarnessConfigStore.file(dir, "s")
        target.parentFile?.mkdirs()
        target.writeText("{\"v\":1,\"noticeReserveTokens\":700}")
        val loaded = HarnessConfigStore.load(dir, "s")
        assertEquals(700, loaded?.noticeReserveTokens)
        assertEquals(HarnessConfig.DEFAULT.postAnchorBudgetTokens, loaded?.postAnchorBudgetTokens)
        assertEquals(HarnessConfig.DEFAULT.ledgerMaxEntries, loaded?.ledgerMaxEntries)
    }
    
    @Test
    fun unsafeSessionIdsStayInsideTheDirectory() {
        val f = HarnessConfigStore.file(dir, "../../etc/passwd")
        assertEquals(dir.resolve(HarnessConfigStore.DIR).absolutePath, f.parentFile.absolutePath)
        assertFalse(f.name.contains("/"))
    }
 }
 
