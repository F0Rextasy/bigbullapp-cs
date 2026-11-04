// Use an integer for version numbers
version = 7

cloudstream {
    description = "DiziLife — dizi83.life dizi ve film arşivi"
    authors = listOf("F0Rextasy")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=dizi83.life&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
