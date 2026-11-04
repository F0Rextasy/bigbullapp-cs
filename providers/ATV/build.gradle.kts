version = 3

cloudstream {
    description = "ATV — Türkçe içerik (autopilot)"
    authors = listOf("F0Rextasy")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=atv.com.tr&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
