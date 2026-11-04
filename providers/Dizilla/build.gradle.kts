version = 10

cloudstream {
    description = "Dizilla — yabancı dizi izle (dizilla.nl mirror'ı)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dizilla.nl&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
