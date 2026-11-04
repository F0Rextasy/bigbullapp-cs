version = 6

cloudstream {
    description = "Filmmodu — Türkçe dublaj & altyazılı film arşivi (filmmodu.news)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=filmmodu.news&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
