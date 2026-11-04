version = 7

cloudstream {
    description = "AsyaLog — Asya dizileri ve filmleri (asyalog.co)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=asyalog.co&sz=%size%"
}
android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
