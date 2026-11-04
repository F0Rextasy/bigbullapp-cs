version = 6

cloudstream {
    description = "TLCtr — TLC Türkiye: gerçek hikayeler, belgesel ve dizi bölümleri + canlı yayın (tlctv.com.tr)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Live")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=tlctv.com.tr&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
