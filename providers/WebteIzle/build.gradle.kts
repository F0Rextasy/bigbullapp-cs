// Use an integer for version numbers
version = 1

cloudstream {
    description = "WebteIzle — Türkçe dublaj/altyazılı film izle (webteizle.info)"
    authors = listOf("F0Rextasy")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 3

    tvTypes = listOf("Movie")
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=webteizle.info&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
