package dev.igun997.fluteisland.core

fun alert(id: String, source: Source = Source.OTHER, text: String = "hello") =
    Alert(id, source, "App", "pkg.$id", "Title $id", text, 0L)

fun media(playing: Boolean = true, title: String = "Song") =
    MediaInfo("com.spotify.music", "Spotify", title, "Artist", playing, 0, 180_000, 0)

fun weather() = Weather(29.4, WeatherKind.CLEAR, true, "Bandung", 31.0, 22.0, 0)

fun ticket(key: String) = Ticket(key, "Summary $key", "Reopened", "High", "https://x.atlassian.net/browse/$key")

fun raw(
    pkg: String = "com.whatsapp",
    title: String? = "Rina",
    text: String? = "Besok jadi?",
    big: String? = null,
    category: String? = "msg",
    ongoing: Boolean = false,
    summary: Boolean = false,
    mediaStyle: Boolean = false,
) = RawNotice("key-$pkg", pkg, "App", title, text, big, category, ongoing, summary, mediaStyle, 1L)
