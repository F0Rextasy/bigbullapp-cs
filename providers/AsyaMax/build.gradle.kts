version = 3

cloudstream {
    description = "AsyaMax — Türkçe altyazılı Asya dizileri ve filmleri (asyamax.com)"
    authors = listOf("F0Rextasy")
    status = 1
    tvTypes = listOf("AsianDrama")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=asyamax.com&sz=%size%"
}

android {
    sourceSets["main"].java.srcDir(rootProject.file("providers/Common/src/main/kotlin"))
}
