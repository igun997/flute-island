package dev.igun997.fluteisland.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandControllerTest {
    private fun controller() = IslandController(alertMs = { 5_000 })

    @Test
    fun firstAlertShowsImmediatelyAndExpires() {
        val c = controller()
        c.postAlert(alert("a"), now = 1_000)
        assertEquals("a", c.snapshot.current?.id)
        assertEquals(6_000L, c.nextDeadline())

        c.tick(5_999)
        assertEquals("a", c.snapshot.current?.id)
        c.tick(6_000)
        assertNull(c.snapshot.current)
        assertNull(c.nextDeadline())
    }

    @Test
    fun secondAlertQueuesThenPromotesWhenFirstExpires() {
        val c = controller()
        c.postAlert(alert("a"), 0)
        c.postAlert(alert("b"), 100)
        assertEquals(listOf("b"), c.snapshot.queue.map { it.id })

        c.tick(5_000)
        assertEquals("b", c.snapshot.current?.id)
        assertEquals(10_000L, c.snapshot.currentUntil)
        assertTrue(c.snapshot.queue.isEmpty())
    }

    @Test
    fun higherPriorityJumpsTheQueueButNeverPreemptsTheVisibleAlert() {
        val c = controller()
        c.postAlert(alert("a", Source.OTHER), 0)
        c.postAlert(alert("b", Source.OTHER), 0)
        c.postAlert(alert("c", Source.WHATSAPP), 0)
        c.postAlert(alert("j", Source.JIRA), 0)
        assertEquals("a", c.snapshot.current?.id)
        assertEquals(listOf("j", "c", "b"), c.snapshot.queue.map { it.id })
    }

    @Test
    fun jiraAlertStaysLongerThanOthers() {
        val c = controller()
        c.postAlert(alert("j", Source.JIRA), 0)
        assertEquals(5_000L + IslandController.JIRA_EXTRA_MS, c.snapshot.currentUntil)
    }

    @Test
    fun sameIdUpdatesInPlaceAndExtendsOnlyWhenContentChanged() {
        val c = controller()
        c.postAlert(alert("a", text = "one"), 0)
        c.postAlert(alert("a", text = "one"), 3_000)
        assertEquals(5_000L, c.snapshot.currentUntil)

        c.postAlert(alert("a", text = "two"), 3_000)
        assertEquals("two", c.snapshot.current?.text)
        assertEquals(8_000L, c.snapshot.currentUntil)
    }

    @Test
    fun sameIdInQueueIsReplacedNotDuplicated() {
        val c = controller()
        c.postAlert(alert("a"), 0)
        c.postAlert(alert("b", text = "old"), 0)
        c.postAlert(alert("b", text = "new"), 0)
        assertEquals(listOf("new"), c.snapshot.queue.map { it.text })
    }

    @Test
    fun queueIsBounded() {
        val c = IslandController(alertMs = { 5_000 }, maxQueue = 3)
        (1..10).forEach { c.postAlert(alert("n$it"), 0) }
        assertEquals(3, c.snapshot.queue.size)
    }

    @Test
    fun retractRemovesCurrentAndPromotesNext() {
        val c = controller()
        c.postAlert(alert("a"), 0)
        c.postAlert(alert("b"), 0)
        c.retract("a", 1_000)
        assertEquals("b", c.snapshot.current?.id)
        c.retract("b", 1_500)
        assertNull(c.snapshot.current)
    }

    @Test
    fun alertsWaitWhileAPanelIsOpenAndAppearWhenItCloses() {
        val c = controller()
        c.setMedia(media(), 0)
        c.togglePanel(0)
        assertEquals(Panel.MEDIA, c.snapshot.panel)

        c.postAlert(alert("a"), 10)
        assertNull(c.snapshot.current)
        assertEquals(1, c.snapshot.queue.size)

        c.togglePanel(2_000)
        assertEquals(Panel.NONE, c.snapshot.panel)
        assertEquals("a", c.snapshot.current?.id)
        assertEquals(7_000L, c.snapshot.currentUntil)
    }

    @Test
    fun tapOpensMostRelevantPanel() {
        val c = controller()
        c.togglePanel(0)
        assertEquals(Panel.NONE, c.snapshot.panel) // nothing to show

        c.setWeather(weather())
        c.togglePanel(0)
        assertEquals(Panel.WEATHER, c.snapshot.panel)
        c.togglePanel(0)

        c.setMode(Mode.TOOLS, 0)
        c.togglePanel(0)
        assertEquals(Panel.TOOLS, c.snapshot.panel)
        c.togglePanel(0)

        c.setMedia(media(), 0)
        c.togglePanel(0)
        assertEquals(Panel.MEDIA, c.snapshot.panel)
    }

    @Test
    fun panelsWithoutDataAreNotOpened() {
        val c = controller()
        c.setPanel(Panel.MEDIA, 0)
        c.setPanel(Panel.WEATHER, 0)
        assertEquals(Panel.NONE, c.snapshot.panel)

        c.setWeather(weather())
        c.setPanel(Panel.WEATHER, 0)
        assertEquals(Panel.WEATHER, c.snapshot.panel)
    }

    @Test
    fun leavingToolsModeClosesToolsPanel() {
        val c = controller()
        c.setMode(Mode.TOOLS, 0)
        c.setPanel(Panel.TOOLS, 0)
        c.setMode(Mode.NORMAL, 1)
        assertEquals(Panel.NONE, c.snapshot.panel)
    }

    @Test
    fun pausedMediaDisappearsAfterGracePeriod() {
        val c = IslandController(mediaGraceMs = 10_000)
        c.setMedia(media(playing = true), 0)
        c.setMedia(media(playing = false), 1_000)
        assertEquals(11_000L, c.nextDeadline())

        c.tick(10_999)
        assertTrue(c.snapshot.media != null)
        c.tick(11_000)
        assertNull(c.snapshot.media)
    }

    @Test
    fun resumingPlaybackCancelsTheGracePeriod() {
        val c = IslandController(mediaGraceMs = 10_000)
        c.setMedia(media(playing = false), 0)
        c.setMedia(media(playing = true), 5_000)
        assertNull(c.nextDeadline())
        c.tick(60_000)
        assertTrue(c.snapshot.media != null)
    }

    @Test
    fun presentationPriorityPanelThenAlertThenMediaThenIdle() {
        val c = controller()
        c.setWeather(weather())
        assertTrue(c.present(0) is Presentation.Idle)

        c.setMedia(media(), 0)
        assertTrue(c.present(0) is Presentation.MediaCompact)

        c.postAlert(alert("a"), 0)
        assertTrue(c.present(0) is Presentation.AlertCard)

        c.setPanel(Panel.MEDIA, 0)
        assertEquals(Presentation.PanelView(Panel.MEDIA), c.present(0))
    }

    @Test
    fun hiddenWhenNothingToShowOrIdleDisabled() {
        val c = controller()
        assertEquals(Presentation.Hidden, c.present(0))

        c.setWeather(weather())
        c.setVisibility(showIdle = false, showWeatherChip = true)
        assertEquals(Presentation.Hidden, c.present(0))

        c.setVisibility(showIdle = true, showWeatherChip = false)
        assertEquals(Presentation.Hidden, c.present(0))
    }

    @Test
    fun toolsModeShowsIdleEvenWithoutWeatherAndCountsTickets() {
        val c = controller()
        c.setMode(Mode.TOOLS, 0)
        c.setTickets(listOf(ticket("A-1"), ticket("A-2")))
        val p = c.present(0) as Presentation.Idle
        assertTrue(p.tools)
        assertEquals(2, p.ticketCount)
        assertNull(p.weather)
    }

    @Test
    fun removingJiraConfigurationClearsTickets() {
        val c = controller()
        c.setJiraConfigured(true)
        c.setTickets(listOf(ticket("A-1")))
        c.setJiraConfigured(false)
        assertTrue(c.snapshot.tickets.isEmpty())
        assertTrue(!c.snapshot.ticketsLoaded)
    }

    @Test
    fun suppressedIslandShowsNothingAndHoldsAlerts() {
        val c = controller()
        c.setWeather(weather())
        c.setSuppressed(true, 0)
        assertEquals(Presentation.Hidden, c.present(0))

        c.postAlert(alert("a").copy(postedAt = 0), 0)
        assertNull(c.snapshot.current)
        assertEquals(listOf("a"), c.snapshot.queue.map { it.id })
        c.togglePanel(0)
        assertEquals(Panel.NONE, c.snapshot.panel)
    }

    @Test
    fun alertsHeldDuringAGamePlayWhenItEnds() {
        val c = controller()
        c.setSuppressed(true, 0)
        c.postAlert(alert("a").copy(postedAt = 10_000), 10_000)
        c.setSuppressed(false, 20_000)
        assertEquals("a", c.snapshot.current?.id)
        assertEquals(25_000L, c.snapshot.currentUntil)
    }

    @Test
    fun staleAlertsAreDroppedAfterALongGame() {
        val c = controller()
        c.setSuppressed(true, 0)
        c.postAlert(alert("old").copy(postedAt = 0), 0)
        c.postAlert(alert("fresh").copy(postedAt = 3_590_000), 3_590_000)
        c.setSuppressed(false, 3_600_000)
        assertEquals("fresh", c.snapshot.current?.id)
        assertTrue(c.snapshot.queue.isEmpty())
    }

    @Test
    fun suppressingClosesAnOpenPanel() {
        val c = controller()
        c.setWeather(weather())
        c.setPanel(Panel.WEATHER, 0)
        c.setSuppressed(true, 1)
        assertEquals(Panel.NONE, c.snapshot.panel)
    }

    @Test
    fun everyAlertIsKeptInTheInboxNewestFirstWithoutDuplicates() {
        val c = controller()
        c.postAlert(alert("a", text = "one"), 0)
        c.postAlert(alert("b"), 0)
        c.postAlert(alert("a", text = "two"), 0)
        assertEquals(listOf("a", "b"), c.snapshot.recent.map { it.id })
        assertEquals("two", c.snapshot.recent.first().text)
    }

    @Test
    fun inboxIsBoundedAndSurvivesRetraction() {
        val c = controller()
        (1..20).forEach { c.postAlert(alert("n$it"), 0) }
        assertEquals(IslandController.MAX_RECENT, c.snapshot.recent.size)
        c.retract("n20", 0) // the notification left the shade (take-over): the inbox keeps it
        assertEquals("n20", c.snapshot.recent.first().id)
    }

    @Test
    fun tapOpensTheInboxWhenThereIsNothingMoreRelevant() {
        val c = controller()
        c.setWeather(weather())
        c.postAlert(alert("a"), 0)
        c.dismissAlert(1)
        c.togglePanel(2)
        assertEquals(Panel.INBOX, c.snapshot.panel)
        c.clearRecent(3)
        assertEquals(Panel.NONE, c.snapshot.panel)
        assertTrue(c.snapshot.recent.isEmpty())
    }

    @Test
    fun swipingSidewaysCyclesThroughPanelsThatHaveContent() {
        val c = controller()
        c.setWeather(weather())
        c.setMedia(media(), 0)
        c.postAlert(alert("a"), 0)
        c.dismissAlert(1)
        c.setPanel(Panel.MEDIA, 2)
        c.cyclePanel(1, 3)
        assertEquals(Panel.INBOX, c.snapshot.panel)
        c.cyclePanel(1, 4)
        assertEquals(Panel.WEATHER, c.snapshot.panel)
        c.cyclePanel(1, 5)
        assertEquals(Panel.MEDIA, c.snapshot.panel)
        c.cyclePanel(-1, 6)
        assertEquals(Panel.WEATHER, c.snapshot.panel)
    }

    @Test
    fun sidewaysSwipeDoesNothingWhenNoPanelIsOpen() {
        val c = controller()
        c.setWeather(weather())
        c.cyclePanel(1, 0)
        assertEquals(Panel.NONE, c.snapshot.panel)
    }
}
