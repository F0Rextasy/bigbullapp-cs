rootProject.name = "CloudstreamPlugins"

// Every directory under providers/ that contains a build.gradle.kts is a plugin module.
// Project path stays :ModuleName, so `./gradlew Dizibox:make` keeps working.

val disabled = listOf<String>()

File(rootDir, "providers").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
