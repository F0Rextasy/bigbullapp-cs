version = 4

cloudstream {
    description = "BelgeselX — Türkçe belgesel arşivi (belgeselx.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=belgeselx.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
