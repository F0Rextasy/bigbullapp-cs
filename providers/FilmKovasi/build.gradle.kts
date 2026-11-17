version = 1


cloudstream {
    description = "Filmkovası — Türkçe dublaj & altyazılı film arşivi (filmkovasi.co)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=filmkovasi.co&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
