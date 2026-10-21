val gitCommitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.get().trim()

val gitCommitNumber = gitCommitHash.filter { it.isDigit() }


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.starfall.generative"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.starfall.generative"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0_$gitCommitNumber"

        manifestPlaceholders["appAuthRedirectScheme"] = "generative-oauth"

        testInstrumentationRunner = if (providers.gradleProperty("migrationUiRunner").orNull == "true") {
            "org.starfall.generative.MigrationUiTestRunner"
        } else "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("CM_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CM_KEY_ALIAS")
            keyPassword = System.getenv("CM_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Opt into an isolated package ID for shareable test APKs without changing release IDs.
            if (providers.gradleProperty("tempAppId").orNull == "true") {
                applicationIdSuffix = ".temp"
            }
        }
        release {
            // Codemagic provides CM_KEYSTORE_PATH and signs in Gradle. On Appcircle it is absent, so the
            // APK stays unsigned here and the "Android Sign" workflow step signs it with the selected keystore.
            if (System.getenv("CM_KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Library-owned drag gestures, placement and edge autoscroll.
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Shared image decoding, request cancellation and caching.
    val coilVersion = "3.0.4"
    implementation("io.coil-kt.coil3:coil-compose:$coilVersion")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coilVersion")
    implementation("io.coil-kt.coil3:coil-video:$coilVersion")
    implementation("io.coil-kt.coil3:coil-svg:$coilVersion")

    // GFM parsing/rendering; code toolbar and streaming presentation remain app-owned.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")

    // Native LaTeX layout for standalone math blocks in chat messages.
    implementation("ru.noties:jlatexmath-android:0.2.0")

    // One playback engine for audio, video and synthesized speech.
    implementation("androidx.media3:media3-exoplayer:1.8.0")

    // OAuth request/PKCE/client-auth mechanics and Custom Tabs lifecycle.
    implementation("net.openid:appauth:0.11.1")

    // Room
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Official JVM SDKs, callable from Kotlin. Ollama keeps its native HTTP adapter.
    implementation("com.openai:openai-java:4.63.1")
    implementation("com.anthropic:anthropic-java:2.62.0")
    implementation("com.google.genai:google-genai:1.71.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")

    // Ktor
    val ktorVersion = "3.5.2"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Official Model Context Protocol client SDK.
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")

    // SSH transport used by the optional Remote Machines service.
    implementation("com.github.mwiede:jsch:0.2.25")
    // Pure-Java Ed25519 for JSch (Android has no JDK 15+ EdDSA provider); see AndroidEdDSA.kt.
    implementation("net.i2p.crypto:eddsa:0.3.0")

    // Termux's maintained terminal renderer/emulator, used for the interactive SSH terminal UI.
    implementation("com.github.termux.termux-app:terminal-view:0.118.3")

    // KotlinX Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
