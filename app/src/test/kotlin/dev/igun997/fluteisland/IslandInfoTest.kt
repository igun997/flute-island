package dev.igun997.fluteisland

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandInfoTest {
    @Test
    fun titleIsStable() {
        assertEquals("Flute Island", IslandInfo.title())
    }
}
