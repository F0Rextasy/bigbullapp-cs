version = 7

cloudstream {
    description = "CizgiSite — Türkçe çizgi film arşivi"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=cizgi.site&sz=%size%"
}

dependencies {
    implementation("org.brotli:dec:0.1.2")
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
