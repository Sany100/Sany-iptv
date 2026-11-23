version = 2

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "Anime with Sub & Dub"
    authors = listOf("KSHITIJ8473")

    status = 1
    // This provider is intentionally limited to episodic anime series.
    // Anime movies are excluded from repository catalog metadata as well.
    tvTypes = listOf(
        "Anime",
        "OVA"
    )
    iconUrl = "https://anilight.live/favicon-96x96.png"
}
