version = 2

cloudstream {
    description = "DiziBol — altyazılı yabancı dizi ve film arşivi (dizibol.org)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dizibol.org&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
