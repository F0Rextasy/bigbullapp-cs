// Use an integer for version numbers
version = 5

cloudstream {
    description = "KoreanTurk — Kore dizileri Türkçe altyazılı (koreanturk.tv)"
    authors = listOf("F0Rextasy")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries")
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=koreanturk.tv&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
