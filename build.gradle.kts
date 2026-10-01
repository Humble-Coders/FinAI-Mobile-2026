plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinCocoapods) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// ── Formatting ───────────────────────────────────────────────────────────────
//
// `./gradlew ktlintCheck` to see what is wrong, `ktlintFormat` to fix what can
// be fixed. CI runs the first and fails on it (#14).
//
// The official ktlint CLI, run as a plain JavaExec, rather than a third-party
// Gradle plugin: the jar is pinned in `libs.versions.toml` with everything
// else, and the build gains no plugin with its own release cadence. What the
// rules *are* lives in `.editorconfig`, which the IDE reads too.
val ktlint: Configuration by configurations.creating {
    // ktlint-cli publishes a plain jar and a shaded one. Asking for the shaded
    // build is not a preference: the plain one arrives without the parser and
    // rule jars it needs, and the ambiguity is a hard resolution failure.
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.SHADOWED))
    }
}

dependencies {
    ktlint(libs.ktlint.cli)
}

// Every Kotlin source we own. Generated code is not ours to format, and
// `build/` holds plenty of it.
private val ktlintTargets = listOf(
    "androidApp/src/**/*.kt",
    "sharedLogic/src/**/*.kt",
    "*.kts",
)

private fun Project.ktlintTask(name: String, format: Boolean) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = if (format) "Fixes what ktlint can fix." else "Checks formatting with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    // `--relative` so a failure names `androidApp/src/...` rather than an
    // absolute path that differs between a laptop and a runner.
    args = buildList {
        if (format) add("--format")
        add("--relative")
        addAll(ktlintTargets)
    }
    // ktlint reads .editorconfig and the sources; Gradle needs to know, or
    // the configuration cache will serve a stale result after an edit.
    inputs.files(
        fileTree(rootDir) {
            include(ktlintTargets)
            exclude("**/build/**")
        },
    )
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file(".editorconfig")).withPropertyName("editorconfig")
    // JDK 17+ closed off the reflection ktlint's formatter uses.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

ktlintTask("ktlintCheck", format = false)
ktlintTask("ktlintFormat", format = true)
