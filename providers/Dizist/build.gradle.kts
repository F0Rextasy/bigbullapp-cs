version = 2

cloudstream {
    description = "Dizist — dizist.live (Türkçe altyazılı yabancı dizi)"
    authors = listOf("F0Rextasy")
    status = 3
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dizist.live&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
