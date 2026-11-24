version = 1


cloudstream {
    description = "WFilmIzle — filmplus film arşivi (wfilmizle.click; WARP/VPN gerekir, içerik çoğunlukla fragman)"
    authors = listOf("F0Rextasy")
    status = 3
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=wfilmizle.click&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
