// Use an integer for version numbers
version = 11


cloudstream {
    description = "DiziMom — yabancı dizi izle, Türkçe dublaj & altyazı (ddizimom.icu)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=ddizimom.icu&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
