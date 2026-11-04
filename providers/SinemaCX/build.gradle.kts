version = 4

cloudstream {
    description = "SinemaCX — Türkçe dublaj & altyazılı film arşivi (sinemacc.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=sinemacc.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
