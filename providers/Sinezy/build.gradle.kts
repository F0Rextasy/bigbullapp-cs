version = 3

cloudstream {
    description = "Sinezy — Türkçe dublaj/altyazılı film arşivi"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=sinezy.si&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
