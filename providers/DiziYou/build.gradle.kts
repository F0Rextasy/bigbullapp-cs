version = 8

cloudstream {
    description = "DiziYou — Türkçe dublaj & altyazılı yabancı dizi (diziyou.one)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=diziyou.one&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
