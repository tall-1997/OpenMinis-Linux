package com.openminis.app.a2a

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-a2a-incognito] A profile binding that the platform accepts but
 * does not apply is invisible from the outside — the login window keeps
 * working, it just shares the default cookie jar. [A2aLoginProfiles.attach]
 * reads the profile back and treats a mismatch as "not isolated", which is
 * the difference between a clean sweep and a silently pre-authenticated
 * login round.
 */
class A2aLoginProfilesTest {

    @Test
    fun `binding is confirmed only when the read-back name matches`() {
        assertTrue(A2aLoginProfiles.bindingMatches("login-mh3k", "login-mh3k"))
    }

    @Test
    fun `default profile read-back means the binding was refused`() {
        assertFalse(A2aLoginProfiles.bindingMatches("login-mh3k", "Default"))
        assertFalse(A2aLoginProfiles.bindingMatches("login-mh3k", null))
        assertFalse(A2aLoginProfiles.bindingMatches("login-mh3k", ""))
    }

    @Test
    fun `no expected profile can never be confirmed`() {
        assertFalse(A2aLoginProfiles.bindingMatches(null, "login-mh3k"))
        assertFalse(A2aLoginProfiles.bindingMatches(null, null))
        assertFalse(A2aLoginProfiles.bindingMatches("", ""))
    }
}
