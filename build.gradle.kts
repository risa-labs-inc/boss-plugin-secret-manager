import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.2.5"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo.
        // NOTE: plugin.json still declares apiVersion 1.0.20 — the
        // ai.rever.boss.plugin.logging and .scrollbar packages used by this plugin
        // were introduced in exactly that release (api tag v1.0.20), and that is
        // still the true floor for the plugin to function.
        //
        // The AI-provider feature compiles against 1.0.71 (LlmProviderSettingsAPI,
        // LlmApiFormat.GOOGLE_GENERATIVE) but does not raise the declared minimum:
        // every reference to those symbols is confined to LlmProviderSettingsApiImpl,
        // which is registered inside a LinkageError guard. On an older host the AI
        // settings panel simply isn't served and secret management is unaffected —
        // raising apiVersion instead would stop the plugin loading at all there.
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.71.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Compose Icons (FeatherIcons)
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization (for JSON parsing of SupabaseDataProvider responses)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tests. The model-list parsers are hand-written from provider docs and had no
    // coverage; these run without a host or a live credential.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // BossLogger binds slf4j at class-init, so a backend is required or every class
    // holding a logger fails with NoClassDefFoundError in tests. The host provides one
    // at runtime; tests have to supply their own.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    // The api is compileOnly (the host supplies it at runtime), so it is absent from the
    // test runtime by default — BossLogger lives there and would fail with
    // NoClassDefFoundError. Tests need it on the classpath explicitly.
    if (useLocalDependencies) {
        testImplementation(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.71.jar"))
    } else {
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-secret-manager-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Secret Manager Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.secretmanager.SecretManagerDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}

// Fat JAR for out-of-process plugin execution
tasks.register<Jar>("shadowJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "ai.rever.boss.plugin.runtime.PluginProcessMainKt"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    from("src/main/resources")
}
