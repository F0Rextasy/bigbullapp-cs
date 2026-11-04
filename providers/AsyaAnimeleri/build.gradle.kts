version = 9

cloudstream {
    description = "AsyaAnimeleri — Türkçe altyazılı anime ve donghua arşivi (asyaanimeleri.top)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("Anime", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=asyaanimeleri.top&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
