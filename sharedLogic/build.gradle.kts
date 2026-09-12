import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.skie)
}

// Declared before the kotlin { } block below uses it — Gradle Kotlin DSL
// initializes vals in script order.
val generatedConfigDir = layout.buildDirectory.dir("generated/finai/config")

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }

    cocoapods {
        name = "SharedLogic"
        version = "1.0"
        summary = "Shared business logic for FinAI (Supabase-backed)"
        homepage = "https://github.com/humblesolutions/finai"
        ios.deploymentTarget = "15.0"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "SharedLogic"
            isStatic = true
        }
        // No third-party pods: Supabase is consumed as a pure Kotlin
        // Multiplatform dependency from commonMain, not as an iOS pod.
    }

    android {
       namespace = "com.humblesolutions.finai.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedConfigDir)
        }
        commonMain.dependencies {
            // Auth and Storage only — clients never talk to the database.
            api(libs.supabase.auth)
            api(libs.supabase.storage)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

skie {
    analytics {
        enabled.set(false)
    }
}

// --- Generated app configuration -------------------------------------------
//
// Supabase URL/anon key and the Render API base URL. Values come from
// local.properties (untracked), never from a committed source file. The anon key is publishable and safe in a client, but committed
// configuration constants are the wrong pattern — and the service_role key must
// never come near this repo. Placeholders are used when local.properties has no
// entry, so a fresh clone still builds.


val generateAppConfig by tasks.registering {
    val outputDir = generatedConfigDir
    val localProperties = rootProject.file("local.properties")
    val url = providers.provider {
        readLocalProperty(localProperties, "supabase.url") ?: "https://REPLACE_ME.supabase.co"
    }
    val anonKey = providers.provider {
        readLocalProperty(localProperties, "supabase.anonKey") ?: "REPLACE_ME_ANON_KEY"
    }
    // Build configuration rather than a source constant, so environments can
    // differ: set api.baseUrl in local.properties to point a build elsewhere.
    val apiBaseUrl = providers.provider {
        readLocalProperty(localProperties, "api.baseUrl") ?: "https://finai-api-7bae.onrender.com"
    }

    // Google OAuth client ids, created in Google Cloud and pasted into
    // local.properties. Not secrets — a client id ships inside every app — but
    // they differ per environment, so they are configuration, not constants.
    val googleWebClientId = providers.provider {
        readLocalProperty(localProperties, "google.webClientId") ?: "REPLACE_ME_GOOGLE_WEB_CLIENT_ID"
    }
    val googleIosClientId = providers.provider {
        readLocalProperty(localProperties, "google.iosClientId") ?: "REPLACE_ME_GOOGLE_IOS_CLIENT_ID"
    }

    inputs.property("url", url)
    inputs.property("anonKey", anonKey)
    inputs.property("apiBaseUrl", apiBaseUrl)
    inputs.property("googleWebClientId", googleWebClientId)
    inputs.property("googleIosClientId", googleIosClientId)
    outputs.dir(outputDir)

    doLast {
        val packageDir = outputDir.get().asFile.resolve("com/humblesolutions/finai/config")
        packageDir.mkdirs()
        packageDir.resolve("SupabaseConfig.kt").writeText(
            buildString {
                appendLine("package com.humblesolutions.finai.config")
                appendLine()
                appendLine("// GENERATED — do not edit.")
                appendLine("// See generateAppConfig in sharedLogic/build.gradle.kts;")
                appendLine("// values come from local.properties, which is not tracked.")
                appendLine("object SupabaseConfig {")
                appendLine("    const val URL: String = \"" + url.get() + "\"")
                appendLine("    const val ANON_KEY: String = \"" + anonKey.get() + "\"")
                appendLine()
                appendLine("    val isConfigured: Boolean")
                appendLine("        get() = !URL.contains(\"REPLACE_ME\") && !ANON_KEY.contains(\"REPLACE_ME\")")
                appendLine("}")
            }
        )
        packageDir.resolve("GoogleConfig.kt").writeText(
            buildString {
                appendLine("package com.humblesolutions.finai.config")
                appendLine()
                appendLine("// GENERATED — do not edit.")
                appendLine("// See generateAppConfig in sharedLogic/build.gradle.kts; values come from")
                appendLine("// google.webClientId / google.iosClientId in local.properties.")
                appendLine("object GoogleConfig {")
                appendLine("    /** Android's Credential Manager sends this as the serverClientId. */")
                appendLine("    const val WEB_CLIENT_ID: String = \"" + googleWebClientId.get() + "\"")
                appendLine("    const val IOS_CLIENT_ID: String = \"" + googleIosClientId.get() + "\"")
                appendLine()
                appendLine("    /** False until the ids are configured, so the UI can say so instead of failing oddly. */")
                appendLine("    val isConfigured: Boolean")
                appendLine("        get() = !WEB_CLIENT_ID.contains(\"REPLACE_ME\") && !IOS_CLIENT_ID.contains(\"REPLACE_ME\")")
                appendLine("}")
            }
        )
        packageDir.resolve("ApiConfig.kt").writeText(
            buildString {
                appendLine("package com.humblesolutions.finai.config")
                appendLine()
                appendLine("// GENERATED — do not edit.")
                appendLine("// See generateAppConfig in sharedLogic/build.gradle.kts; the value comes from")
                appendLine("// api.baseUrl in local.properties, falling back to the build default.")
                appendLine("object ApiConfig {")
                appendLine("    const val BASE_URL: String = \"" + apiBaseUrl.get() + "\"")
                appendLine("}")
            }
        )
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateAppConfig)
}

fun readLocalProperty(file: File, key: String): String? {
    if (!file.exists()) return null
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    return properties.getProperty(key)?.takeIf { value -> value.isNotBlank() }
}
