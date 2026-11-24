package dev.igun997.fluteisland.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationMapperTest {
    private val own = "dev.igun997.fluteisland"
    private val blocked = NotificationMapper.DEFAULT_BLOCKED

    private fun map(r: RawNotice, b: Set<String> = blocked) = NotificationMapper.map(r, own, b)

    @Test
    fun whatsappMessageBecomesWhatsappAlert() {
        val a = map(raw())!!
        assertEquals(Source.WHATSAPP, a.source)
        assertEquals("Rina", a.title)
        assertEquals("Besok jadi?", a.text)
        assertEquals("key-com.whatsapp", a.id)
    }

    @Test
    fun businessWhatsappIsAlsoWhatsapp() {
        assertEquals(Source.WHATSAPP, map(raw(pkg = "com.whatsapp.w4b"))!!.source)
    }

    @Test
    fun otherAppsAreOther() {
        assertEquals(Source.OTHER, map(raw(pkg = "org.telegram.messenger"))!!.source)
    }

    @Test
    fun droppedKindsNeverReachTheIsland() {
        assertNull(map(raw(ongoing = true)))
        assertNull(map(raw(summary = true)))
        assertNull(map(raw(mediaStyle = true)))
        assertNull(map(raw(category = "transport")))
        assertNull(map(raw(category = "progress")))
        assertNull(map(raw(category = "call")))
    }

    @Test
    fun ownAndBlockedPackagesAreDropped() {
        assertNull(map(raw(pkg = own)))
        assertNull(map(raw(pkg = "com.android.systemui")))
        assertNull(map(raw(pkg = "com.whatsapp"), setOf("com.whatsapp")))
    }

    @Test
    fun emptyNotificationIsDropped() {
        assertNull(map(raw(title = " ", text = null, big = "")))
    }

    @Test
    fun bigTextIsTheFallbackBody() {
        assertEquals("long body", map(raw(text = null, big = "long body"))!!.text)
    }

    @Test
    fun missingTitleFallsBackToAppName() {
        assertEquals("App", map(raw(title = null, text = "hi"))!!.title)
    }

    @Test
    fun whitespaceIsCollapsedAndLongTextTruncated() {
        val a = map(raw(title = "a \n  b", text = "x".repeat(500)))!!
        assertEquals("a b", a.title)
        assertEquals(NotificationMapper.MAX_TEXT, a.text.length)
        assertEquals('\u2026', a.text.last())
        assertNotNull(a)
    }
}
