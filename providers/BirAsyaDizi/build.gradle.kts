version = 10


cloudstream {
    description = "BirAsyaDizi — Türkçe içerik"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("AsianDrama")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=birasyadizi.cx&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
