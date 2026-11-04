// Use an integer for version numbers
version = 6

cloudstream {
    description = "JetFilmizle — Türkçe dublaj/altyazılı film izle (jetfilmizle.app)"
    authors = listOf("F0Rextasy")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie")
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=jetfilmizle.app&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
