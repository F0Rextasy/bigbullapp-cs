// Use an integer for version numbers
version = 4

cloudstream {
    description = "DDizi — Türkçe dizi izle (ddizi.im → ddizi.tel)"
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

    iconUrl = "https://www.google.com/s2/favicons?domain=ddizi.tel&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
