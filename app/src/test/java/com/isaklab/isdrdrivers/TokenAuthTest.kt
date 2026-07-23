package com.isaklab.isdrdrivers

import java.lang.reflect.Method
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN token compare must be constant-time and exact — the gate that
 * keeps an unauthenticated client off the radio. Reached by reflection since
 * it is a private companion helper (no Android deps).
 */
class TokenAuthTest {

    private val match: Method = Class.forName(
        "com.isaklab.isdrdrivers.DriverSession\$Companion",
    ).getDeclaredMethod("tokensMatch", String::class.java, String::class.java)
        .apply { isAccessible = true }

    private val companion: Any = Class.forName("com.isaklab.isdrdrivers.DriverSession")
        .getDeclaredField("Companion").apply { isAccessible = true }.get(null)

    private fun eq(a: String, b: String) = match.invoke(companion, a, b) as Boolean

    @Test fun equalTokensMatch() {
        assertTrue(eq("a-strong-256-bit-secret", "a-strong-256-bit-secret"))
    }

    @Test fun differentTokensDoNotMatch() {
        assertFalse(eq("secret-A", "secret-B"))
    }

    @Test fun lengthMismatchDoesNotMatch() {
        assertFalse(eq("short", "short-plus-more"))
    }

    @Test fun emptyNeverMatchesNonEmpty() {
        assertFalse(eq("", "x"))
    }
}
