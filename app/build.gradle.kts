import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.talkcan"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "29.0.14206865"
    val releaseKeystorePath = System.getenv("ANDROID_RELEASE_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
    val releaseSigningValues = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyPassword,
    )
    val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
    require(releaseSigningValues.none { !it.isNullOrBlank() } || releaseSigningConfigured) {
        "Release signing requires keystore path, keystore password, and key password together"
    }

    defaultConfig {
        applicationId = "io.talkcan"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = "talkcan-release"
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // Only `src/main/assets` is bundled. Model ONNX/JSON files are
            // downloaded at runtime by `ModelDownloader`; the build emits only
            // `model-hashes.json` (via `generateModelHashes`) into this dir.
            assets.srcDirs("src/main/assets")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

val luaJavaVersion = libs.versions.luajava.get()

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation(libs.luajava.lua54)
    runtimeOnly("party.iroiro.luajava:android:$luaJavaVersion:lua54@aar")
    implementation(project(":sleepwalker-core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("party.iroiro.luajava:lua54-platform:$luaJavaVersion:natives-desktop")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20231013")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("com.microsoft.onnxruntime:onnxruntime:1.24.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}


// ---------------------------------------------------------------------------
// Parakeet model asset download (int8 ONNX, vocab, config)
// ---------------------------------------------------------------------------

val parakeetAssetDir = layout.buildDirectory.dir("generated/assets/parakeet")

val parakeetAssetSpecs = listOf(
    ParakeetAsset(
        name = "encoder-model.int8.onnx",
        sha256 = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
    ),
    ParakeetAsset(
        name = "decoder_joint-model.int8.onnx",
        sha256 = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
    ),
    ParakeetAsset(
        name = "nemo128.onnx",
        sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
    ),
    ParakeetAsset(
        name = "vocab.txt",
        sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
    ),
    ParakeetAsset(
        name = "config.json",
        sha256 = "666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466",
    ),
)

val downloadParakeetAssets by tasks.registering {
    group = "parakeet"
    description = "Downloads Parakeet v3 int8 ONNX model, vocab, and config from the Talkcan CDN with SHA-256 verification."
    val outDir = parakeetAssetDir
    outputs.dir(outDir)
    parakeetAssetSpecs.forEach { spec ->
        outputs.file(outDir.map { it.file(spec.name) })
    }
    doLast {
        outDir.get().asFile.mkdirs()
        val repo = "smcleod/parakeet-tdt-0.6b-v3-int8"
        logger.lifecycle("Downloading Parakeet assets from CDN repo {}", repo)
        parakeetAssetSpecs.forEach { spec ->
            val target = outDir.get().file(spec.name).asFile
            downloadFromCdn(repo, spec.name, target, spec.sha256)
            logger.lifecycle("Parakeet asset {} verified", spec.name)
        }
    }
}


// ---------------------------------------------------------------------------
// Supertonic model asset download (ONNX, configs, voice styles)
// ---------------------------------------------------------------------------

val supertonicAssetDir = layout.buildDirectory.dir("generated/assets/supertonic")

// Supertonic 3 assets live under `onnx/` and `voice_styles/` in the
// `Supertone/supertonic-3` HuggingFace repo. They are downloaded into the flat
// generated assets dir (flattening the HF subdir layout) so the Android asset
// source set and `SupertonicAssetExtractor` see them at the top level.
val supertonicAssetSpecs = listOf(
    SupertonicAsset(
        hfPath = "onnx/duration_predictor.onnx",
        localName = "duration_predictor.onnx",
        sha256 = "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
    ),
    SupertonicAsset(
        hfPath = "onnx/text_encoder.onnx",
        localName = "text_encoder.onnx",
        sha256 = "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
    ),
    SupertonicAsset(
        hfPath = "onnx/vector_estimator.onnx",
        localName = "vector_estimator.onnx",
        sha256 = "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
    ),
    SupertonicAsset(
        hfPath = "onnx/vocoder.onnx",
        localName = "vocoder.onnx",
        sha256 = "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
    ),
    SupertonicAsset(
        hfPath = "onnx/tts.json",
        localName = "tts.json",
        sha256 = "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
    ),
    SupertonicAsset(
        hfPath = "onnx/unicode_indexer.json",
        localName = "unicode_indexer.json",
        sha256 = "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F1.json",
        localName = "F1.json",
        sha256 = "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F2.json",
        localName = "F2.json",
        sha256 = "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F3.json",
        localName = "F3.json",
        sha256 = "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F4.json",
        localName = "F4.json",
        sha256 = "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/F5.json",
        localName = "F5.json",
        sha256 = "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M1.json",
        localName = "M1.json",
        sha256 = "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M2.json",
        localName = "M2.json",
        sha256 = "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M3.json",
        localName = "M3.json",
        sha256 = "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M4.json",
        localName = "M4.json",
        sha256 = "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303",
    ),
    SupertonicAsset(
        hfPath = "voice_styles/M5.json",
        localName = "M5.json",
        sha256 = "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2",
    ),
)

val downloadSupertonicAssets by tasks.registering {
    group = "supertonic"
    description = "Downloads Supertonic 3 ONNX models, configs, and voice styles from the Talkcan CDN with SHA-256 verification."
    val outDir = supertonicAssetDir
    outputs.dir(outDir)
    supertonicAssetSpecs.forEach { spec ->
        outputs.file(outDir.map { it.file(spec.localName) })
    }
    doLast {
        outDir.get().asFile.mkdirs()
        val repo = "Supertone/supertonic-3"
        logger.lifecycle("Downloading Supertonic assets from CDN repo {}", repo)
        supertonicAssetSpecs.forEach { spec ->
            val target = outDir.get().file(spec.localName).asFile
            downloadFromCdn(repo, spec.hfPath, target, spec.sha256)
            logger.lifecycle("Supertonic asset {} verified", spec.localName)
        }
    }
}


// ---------------------------------------------------------------------------
// model-hashes.json manifest generation
// ---------------------------------------------------------------------------

/**
 * Model set metadata consumed by both the device-side `ModelDownloader` and
 * the Gradle `generateModelHashes` task. The `version` is written to
 * `.talkcan_assets_version` on disk after a successful download so the
 * device can detect version drift. The `repo` is the HuggingFace Hub
 * identifier used to build download URLs. Manifest `files` keys are HF paths
 * (flat for parakeet, `onnx/`- or `voice_styles/`-prefixed for supertonic);
 * the downloader saves each file to `{filesDir}/{dir}/{basename}`.
 */
val parakeetModelSet = ModelSetSpec(
    dirName = "parakeet-tdt-0.6b-v3-int8",
    version = "int8-2026-06-23",
    repo = "smcleod/parakeet-tdt-0.6b-v3-int8",
    files = parakeetAssetSpecs.map { it.name to it.sha256 },
)

val supertonicModelSet = ModelSetSpec(
    dirName = "supertonic-3",
    version = "supertonic-3-2026-06-24",
    repo = "Supertone/supertonic-3",
    files = supertonicAssetSpecs.map { it.hfPath to it.sha256 },
)

val modelHashesManifest = layout.projectDirectory.file("src/main/assets/model-hashes.json")

val generateModelHashes by tasks.registering {
    group = "model"
    description = "Writes src/main/assets/model-hashes.json from the Parakeet/Supertonic asset specs."
    val sets = listOf(parakeetModelSet, supertonicModelSet)
    val outFile = modelHashesManifest
    outputs.file(outFile)
    doLast {
        outFile.asFile.parentFile.mkdirs()
        val sb = StringBuilder()
        sb.append("{\n")
        sets.forEachIndexed { index, set ->
            if (index > 0) sb.append(",\n")
            sb.append("  \"").append(set.dirName).append("\": {\n")
            sb.append("    \"version\": \"").append(set.version).append("\",\n")
            sb.append("    \"repo\": \"").append(set.repo).append("\",\n")
            sb.append("    \"files\": {")
            if (set.files.isEmpty()) {
                sb.append("}\n")
            } else {
                sb.append("\n")
                set.files.forEachIndexed { fidx, (name, hash) ->
                    if (fidx > 0) sb.append(",\n")
                    sb.append("      \"").append(name).append("\": \"sha256:").append(hash).append("\"")
                }
                sb.append("\n    }\n")
            }
            sb.append("  }")
        }
        sb.append("\n}\n")
        val target = outFile.asFile
        target.writeText(sb.toString())
        logger.lifecycle("Wrote model hash manifest to {}", target.absolutePath)
    }
}

// generateModelHashes writes model-hashes.json from the hardcoded asset specs
// (parakeetAssetSpecs / supertonicAssetSpecs). The model ONNX/JSON files are
// downloaded at runtime by ModelDownloader from the CDN; the build never
// bundles them, so the download tasks must not gate manifest generation or
// CI. downloadParakeetAssets / downloadSupertonicAssets remain available as
// manual utilities for local asset extraction but are no longer in the
// preBuild dependency chain.

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateModelHashes)
}

data class ModelSetSpec(
    val dirName: String,
    val version: String,
    val repo: String,
    val files: List<Pair<String, String>>,
)

fun sha256OfFile(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * CDN base mirroring the HuggingFace repos used at build time. Matches the
 * device-side `ModelDownloader.HF_BASE` so both build and runtime fetch from
 * the same origin.
 */
val MODEL_CDN_BASE = "https://pub-8f2960e0f9214d289ad6f09e0148d5b1.r2.dev"

/**
 * Download [hfPath] from `MODEL_CDN_BASE/{repo}/resolve/main/{hfPath}` into
 * [target], verifying SHA-256 on completion. Streams to a temp file first,
 * then renames, so a partial or corrupt download never replaces a good file.
 */
fun downloadFromCdn(repo: String, hfPath: String, target: File, expectedSha256: String) {
    val url = "$MODEL_CDN_BASE/$repo/resolve/main/$hfPath"
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            check(code == 200) { "HTTP $code for $url" }
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = sha256OfFile(tmp)
        check(actual == expectedSha256) {
            "SHA-256 mismatch for ${target.name}: expected $expectedSha256, got $actual"
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    } catch (e: Exception) {
        tmp.delete()
        throw GradleException("CDN download failed for $url: ${e.message}", e)
    }
}

data class ParakeetAsset(
    val name: String,
    val sha256: String,
)

data class SupertonicAsset(
    val hfPath: String,
    val localName: String,
    val sha256: String,
)
