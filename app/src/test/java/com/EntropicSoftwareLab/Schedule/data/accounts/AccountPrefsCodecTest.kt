package com.EntropicSoftwareLab.Schedule.data.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPrefsCodecTest {

    @Test
    fun `a full prefs object round trips`() {
        val prefs = AccountPrefs(
            added = listOf(
                AddedAccount("a@gmail.com", "com.google", "Personal"),
                AddedAccount("b@uni.edu", "com.google"),
            ),
            hiddenAccounts = setOf("b@uni.edu"),
            hiddenCalendarIds = setOf(7L, 12L),
            birthdaysShown = false,
            hiddenBirthdayKeys = setOf("sarah|3|4", "ahmad|11|30"),
        )
        assertEquals(prefs, AccountPrefsCodec.decode(AccountPrefsCodec.encode(prefs)))
    }

    @Test
    fun `defaults round trip`() {
        assertEquals(AccountPrefs(), AccountPrefsCodec.decode(AccountPrefsCodec.encode(AccountPrefs())))
    }

    @Test
    fun `null blank and corrupt blobs decode to defaults`() {
        assertEquals(AccountPrefs(), AccountPrefsCodec.decode(null))
        assertEquals(AccountPrefs(), AccountPrefsCodec.decode(""))
        assertEquals(AccountPrefs(), AccountPrefsCodec.decode("   "))
        assertEquals(AccountPrefs(), AccountPrefsCodec.decode("{garbage"))
    }

    @Test
    fun `missing birthdaysShown decodes as shown`() {
        val decoded = AccountPrefsCodec.decode("""{"hiddenAccounts":["x@y.z"]}""")
        assertTrue(decoded.birthdaysShown)
        assertEquals(setOf("x@y.z"), decoded.hiddenAccounts)
    }

    @Test
    fun `an added account with a blank name is dropped`() {
        val decoded = AccountPrefsCodec.decode(
            """{"added":[{"name":"","type":"com.google"},{"name":"  ","type":"t"},{"name":"ok@x.y","type":"t","nickname":"Work"}]}""",
        )
        assertEquals(listOf(AddedAccount("ok@x.y", "t", "Work")), decoded.added)
    }
}
