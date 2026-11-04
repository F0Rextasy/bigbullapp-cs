version = 4

cloudstream {
    description = "FullHDFilm — fullhdfilmizlebox.vip (Türkçe dublaj & altyazı)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=fullhdfilmizlebox.vip&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
