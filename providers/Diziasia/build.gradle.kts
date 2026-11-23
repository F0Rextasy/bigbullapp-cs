version = 8


cloudstream {
    description = "DiziAsia — Asya dizileri ve filmleri (diziasia.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("AsianDrama")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=diziasia.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
