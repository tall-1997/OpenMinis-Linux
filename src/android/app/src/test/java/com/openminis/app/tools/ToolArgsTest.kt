package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [P2-convergence] Default-value matrix for the shared tool-argument readers.
 * These pin the two numeric dialects that must NOT be unified: the device
 * tools' lenient [ToolArgs.int] ("12.5" truncates) and the virtual-screen
 * tools' strict [ToolArgs.strictInt] ("12.5" is absent).
 */
class ToolArgsTest {

    @Test
    fun strTreatsMissingNullAndBlankAsAbsent() {
        val args = JSONObject().put("a", " x ").put("b", "").put("n", JSONObject.NULL)
        assertEquals("x", ToolArgs.str(args, "a"))
        assertNull(ToolArgs.str(args, "b"))
        assertNull(ToolArgs.str(args, "n"))
        assertNull(ToolArgs.str(args, "missing"))
    }

    @Test
    fun intIsLenientWithNumericStrings() {
        val args = JSONObject()
            .put("num", 12)
            .put("str", "34")
            .put("frac", "12.5")
            .put("junk", "abc")
            .put("n", JSONObject.NULL)
        assertEquals(12, ToolArgs.int(args, "num"))
        assertEquals(34, ToolArgs.int(args, "str"))
        // Device-tools dialect: numeric strings go through toDoubleOrNull.
        assertEquals(12, ToolArgs.int(args, "frac"))
        assertNull(ToolArgs.int(args, "junk"))
        assertNull(ToolArgs.int(args, "n"))
        assertNull(ToolArgs.int(args, "missing"))
    }

    @Test
    fun strictIntRefusesNonIntegerStrings() {
        val args = JSONObject()
            .put("num", 12)
            .put("str", "34")
            .put("frac", "12.5")
            .put("spaced", " 56 ")
        assertEquals(12, ToolArgs.strictInt(args, "num"))
        assertEquals(34, ToolArgs.strictInt(args, "str"))
        // Virtual-screen dialect: a repeat cannot be trusted with a float.
        assertNull(ToolArgs.strictInt(args, "frac"))
        assertEquals(56, ToolArgs.strictInt(args, "spaced"))
    }

    @Test
    fun boolCoversNumbersStringsAndJsonBooleans() {
        val args = JSONObject()
            .put("t", true)
            .put("f", false)
            .put("yes", "yes")
            .put("one", "1")
            .put("zero", "0")
            .put("nonzero", 7)
            .put("zeroNum", 0)
            .put("junk", "maybe")
        assertEquals(true, ToolArgs.bool(args, "t"))
        assertEquals(false, ToolArgs.bool(args, "f"))
        assertEquals(true, ToolArgs.bool(args, "yes"))
        assertEquals(true, ToolArgs.bool(args, "one"))
        assertEquals(false, ToolArgs.bool(args, "zero"))
        assertEquals(true, ToolArgs.bool(args, "nonzero"))
        assertEquals(false, ToolArgs.bool(args, "zeroNum"))
        assertNull(ToolArgs.bool(args, "junk"))
        assertNull(ToolArgs.bool(args, "missing"))
    }

    @Test
    fun strListAcceptsArraySingleStringAndRejectsEmpty() {
        val args = JSONObject()
            .put("arr", org.json.JSONArray().put(" a ").put("b").put(""))
            .put("single", " solo ")
            .put("emptyArr", org.json.JSONArray())
            .put("blank", "   ")
            .put("obj", JSONObject())
        assertEquals(listOf("a", "b"), ToolArgs.strList(args, "arr"))
        assertEquals(listOf("solo"), ToolArgs.strList(args, "single"))
        // An array of only blanks and an empty array both count as absent.
        assertNull(ToolArgs.strList(args, "emptyArr"))
        assertNull(ToolArgs.strList(args, "blank"))
        assertNull(ToolArgs.strList(args, "obj"))
        assertNull(ToolArgs.strList(args, "missing"))
    }

    @Test
    fun longAndOptDoubleMirrorTheIntContract() {
        val args = JSONObject()
            .put("num", 12L)
            .put("str", "34")
            .put("fracStr", "12.9")
            .put("d", 1.5)
            .put("dStr", " 2.5 ")
            .put("junk", "x")
        assertEquals(12L, ToolArgs.long(args, "num"))
        assertEquals(34L, ToolArgs.long(args, "str"))
        assertEquals(12L, ToolArgs.long(args, "fracStr"))
        assertEquals(1.5, ToolArgs.optDouble(args, "d")!!, 1e-9)
        assertEquals(2.5, ToolArgs.optDouble(args, "dStr")!!, 1e-9)
        assertNull(ToolArgs.optDouble(args, "junk"))
        assertNull(ToolArgs.optDouble(args, "missing"))
    }
}
