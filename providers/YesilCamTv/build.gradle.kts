version = 2

cloudstream {
    description = "YesilCamTv — Türkçe içerik (autopilot)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=yesilcamtv.com.tr&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
