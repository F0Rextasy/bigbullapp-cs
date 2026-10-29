plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("runner.MainKt") }

sourceSets {
    main {
        kotlin.srcDir("generated")
        kotlin.include("**/*.kt")
    }
}
