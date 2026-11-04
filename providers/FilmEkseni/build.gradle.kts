version = 4

cloudstream {
    description = "FilmEkseni — Türkçe dublaj film arşivi (filmekseni.vip)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=filmekseni.vip&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
