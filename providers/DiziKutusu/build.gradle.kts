version = 4
cloudstream {
    description = "DiziKutusu — Batı & Asya dizileri arşivi (dizikutusu.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=dizikutusu.com&sz=%size%"
}
android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
