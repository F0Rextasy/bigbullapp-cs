rootProject.name = "CloudstreamPlugins"

// Every directory under providers/ that contains a build.gradle.kts with status != 0 is a plugin module.
// Project path stays :ModuleName, so `./gradlew Dizibox:make` keeps working.
// Pasif modüller kendi build.gradle.kts dosyasında `status = 0` ile işaretlenir (tek kaynak).
val statusZero = Regex("""^\s*status\s*=\s*0\b""", RegexOption.MULTILINE)

File(rootDir, "providers").eachDir { dir ->
    val buildFile = File(dir, "build.gradle.kts")
    if (buildFile.exists() && !statusZero.containsMatchIn(buildFile.readText())) {
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
