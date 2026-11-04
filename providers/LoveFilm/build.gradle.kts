version = 5

cloudstream {
    description = "LoveFilm — Türkçe dublaj & altyazılı film arşivi (lovefilmizle.net)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=lovefilmizle.net&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
