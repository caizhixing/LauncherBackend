package com.caizhixing.feedback.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpResolverTest {

    @Test
    fun firstXffIpWins() {
        assertEquals("203.0.113.7", ClientIpResolver.resolve("203.0.113.7, 198.51.100.9, 10.0.0.1", "10.0.0.1"))
    }

    @Test
    fun singleXffValueUsed() {
        assertEquals("203.0.113.7", ClientIpResolver.resolve("203.0.113.7", "10.0.0.1"))
    }

    @Test
    fun xffSurroundingSpacesTrimmed() {
        assertEquals("203.0.113.7", ClientIpResolver.resolve("  203.0.113.7 , 198.51.100.9", "10.0.0.1"))
    }

    @Test
    fun blankXffFallsBackToRemoteHost() {
        assertEquals("10.0.0.1", ClientIpResolver.resolve("", "10.0.0.1"))
        assertEquals("10.0.0.1", ClientIpResolver.resolve("   ", "10.0.0.1"))
        assertEquals("10.0.0.1", ClientIpResolver.resolve(" , 198.51.100.9", "10.0.0.1"))
    }

    @Test
    fun missingXffFallsBackToRemoteHost() {
        assertEquals("10.0.0.1", ClientIpResolver.resolve(null, "10.0.0.1"))
    }
}
