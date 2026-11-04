// Use an integer for version numbers
version = 8

cloudstream {
    description = "DiziMom — yabancı dizi izle, Türkçe dublaj & altyazı (ddizimom.net)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=ddizimom.net&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
