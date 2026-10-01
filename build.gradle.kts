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
// `build/` holds plenty of it — hence the negation, which matters only for
// the `.kts` pattern: the `.kt` ones are already confined to `src/`.
//
// `**/*.kts` rather than `*.kts`. The bare form is not recursive: it matched
// the two build files at the root and silently skipped `androidApp/` and
// `sharedLogic/`, which is to say the module build file most likely to be
// edited was the one nothing checked.
private val ktlintTargets = listOf(
    "androidApp/src/**/*.kt",
    "sharedLogic/src/**/*.kt",
    "**/*.kts",
    "!**/build/**",
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
    // Declaring the inputs is only half of up-to-date checking — a task with
    // no outputs re-runs every time regardless. So give it one: an empty
    // marker whose timestamp says "these sources passed". Re-running ktlint
    // over an unchanged tree is a few seconds, but a few seconds on every
    // `build` is the kind of tax nobody attributes to the thing causing it.
    val sources = fileTree(rootDir) {
        include(ktlintTargets)
        exclude("**/build/**")
    }
    inputs.files(sources)
        .withPropertyName("sources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file(".editorconfig")).withPropertyName("editorconfig")
    // Formatting rewrites the sources, so its "result" is the tree itself and
    // a marker would be a lie. Only the check is up-to-date-able.
    if (format) {
        outputs.upToDateWhen { false }
    } else {
        // Resolved here, at configuration time, and the task action closes over
        // the Provider. Calling `layout.buildDirectory` inside `doLast` instead
        // would capture the Project, which the configuration cache refuses to
        // serialize — and it refuses at the end of the build, not the start.
        val marker = layout.buildDirectory.file("ktlint/$name.passed")
        outputs.file(marker)
        doLast {
            marker.get().asFile.apply {
                parentFile.mkdirs()
                writeText("")
            }
        }
    }
    // JDK 17+ closed off the reflection ktlint's formatter uses.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

ktlintTask("ktlintCheck", format = false)
ktlintTask("ktlintFormat", format = true)
