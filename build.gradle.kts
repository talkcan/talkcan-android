plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register<Test>("test") {
    group = "verification"
    description = "Runs unit tests from subprojects."

    evaluationDependsOn(":app")
    evaluationDependsOn(":sleepwalker-core")

    val appProject = project(":app")
    val coreProject = project(":sleepwalker-core")

    val appTestTask = appProject.tasks.named<Test>("testDebugUnitTest").get()
    val coreTestTask = coreProject.tasks.named<Test>("testDebugUnitTest").get()

    testClassesDirs = files(appTestTask.testClassesDirs, coreTestTask.testClassesDirs)
    classpath = files(appTestTask.classpath, coreTestTask.classpath)

    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/test/binary"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/test"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/test"))

    dependsOn(appTestTask)
    dependsOn(coreTestTask)

    // Replace the aggregate 'test' tasks from subprojects to prevent 'Unknown command-line option --tests'
    appProject.tasks.replace("test", org.gradle.api.tasks.testing.Test::class.java).apply {
        testClassesDirs = files()
        classpath = files()
    }
    coreProject.tasks.replace("test", org.gradle.api.tasks.testing.Test::class.java).apply {
        testClassesDirs = files()
        classpath = files()
    }

    // Propagate command-line filter to all subproject test tasks of type Test when task graph is ready
    gradle.taskGraph.whenReady {
        val getCmdPatterns = filter::class.java.getMethod("getCommandLineIncludePatterns")
        val filterSpecs = getCmdPatterns.invoke(filter) as? Set<*>
        if (filterSpecs != null && filterSpecs.isNotEmpty()) {
            subprojects {
                tasks.withType<Test>().configureEach {
                    filter.setFailOnNoMatchingTests(false)
                    filterSpecs.forEach { spec ->
                        filter.includeTestsMatching(spec.toString())
                    }
                }
            }
        }
    }
}
// ---------------------------------------------------------------------------
// Override :sleepwalker-core build config and generate OmniKeymap resources
// ---------------------------------------------------------------------------
// The sleepwalker-core module may live in a read-only Nix store path (via
// SLEEPWALKER_CORE_PATH). Its upstream build.gradle.kts targets compileSdk 34
// with no buildToolsVersion; we override to 35 / 35.0.0 to match the SDK
// provided by the Talkcan flake. The OmniKeymap JSON files are copied into a
// generated res/raw directory and added to the source set.

// The upstream build.gradle.kts targets compileSdk 34 with no buildToolsVersion;
// override to 35 / 35.0.0 to match the SDK provided by the Talkcan flake.
// The Android extension is only available after the plugin is applied, so
// configure in afterEvaluate.

project(":sleepwalker-core").afterEvaluate {
    val ext = extensions.getByType<com.android.build.gradle.LibraryExtension>()
    ext.apply {
        compileSdk = 35
        buildToolsVersion = "35.0.0"
    }

    val omniKeymapPath = providers.environmentVariable("OMNI_KEYMAP_PATH")
    val generatedKeymapDir = rootProject.layout.buildDirectory.dir("generated/keymap-res/raw")
    val generatedKeymapFile = generatedKeymapDir.get().asFile
    val generatedResRoot = generatedKeymapFile.parentFile // build/generated/keymap-res/

    // Add the generated keymap res root to :sleepwalker-core's main source set.
    // AGP expects srcDir to be a res root (containing raw/, layout/, etc. subdirs).
    // We copy files into raw/, so srcDir is the parent (keymap-res/).
    ext.sourceSets.getByName("main").res.srcDir(generatedResRoot)
    val copyKeymapResources = tasks.register<Copy>("copyKeymapResources") {
        group = "keymap"
        description = "Copies OmniKeymap layout JSONs from OMNI_KEYMAP_PATH into generated res/raw."

        // Nix store files are 0444; make the copies writable so Gradle can
        // overwrite them on subsequent builds.
        filePermissions { unix("0644") }

        omniKeymapPath.orElse("").get().let { path ->
            if (path.isBlank()) {
                logger.warn("OMNI_KEYMAP_PATH not set; skipping keymap resource copy. " +
                    "JsonKeymapDatabase will be empty, SeedKeymapDatabase is the fallback.")
                return@register
            }
            listOf("linux", "macos", "windows").forEach { platform ->
                from("$path/database/$platform") {
                    include("*.json")
                    rename { filename ->
                        val stem = filename.removeSuffix(".json")
                            .replace("+", "_")
                            .replace("-", "_")
                            .lowercase()
                        "keymap_${platform}_${stem}.json"
                    }
                }
            }
            into(generatedKeymapFile)
        }
    }

    // Wire copyKeymapResources to run before AGP resource tasks.
    // These tasks are created lazily, so use configureEach to catch them.
    tasks.configureEach {
        if (name == "generateDebugResources" || name == "generateReleaseResources" ||
            name == "mergeDebugResources" || name == "mergeReleaseResources" ||
            name == "packageDebugResources" || name == "packageReleaseResources" ||
            name == "mapDebugSourceSetPaths" || name == "mapReleaseSourceSetPaths") {
            dependsOn(copyKeymapResources)
        }
    }
}
