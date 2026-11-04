// Use an integer for version numbers
version = 9

cloudstream {
    description = "Anizm — Türkçe altyazılı anime izle (turkanime.info)"
    authors = listOf("F0Rextasy")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime")
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=turkanime.info&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
