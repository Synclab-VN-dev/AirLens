import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val synclabVersionFile = rootProject.file("synclab-version.gradle")
val synclabVersionText = synclabVersionFile.readText()
val synclabVersionName = Regex("""(?m)^\s*versionName\s+"([^"]+)"\s*$""")
    .find(synclabVersionText)
    ?.groupValues
    ?.get(1)
    ?: error("Missing versionName in ${synclabVersionFile.path}")
val synclabVersionCode = Regex("""(?m)^\s*versionCode\s+(\d+)\s*$""")
    .find(synclabVersionText)
    ?.groupValues
    ?.get(1)
    ?: error("Missing versionCode in ${synclabVersionFile.path}")

val openStreamVersionName = providers.gradleProperty("openstream.versionName")
    .orElse(providers.environmentVariable("OPENSTREAM_VERSION_NAME"))
    .orElse(synclabVersionName)
    .map { it.removePrefix("v") }
val openStreamVersionCode = providers.gradleProperty("openstream.versionCode")
    .orElse(providers.environmentVariable("OPENSTREAM_VERSION_CODE"))
    .orElse(synclabVersionCode)
    .map { it.toInt() }

android {
    namespace = "com.synclab.airlens"
    compileSdk = 36
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.synclab.airlens"
        minSdk = 29
        targetSdk = 36
        versionCode = openStreamVersionCode.get()
        versionName = openStreamVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                val nonStreamingCiBuild =
                    providers.gradleProperty("openstream.nonStreamingCiBuild").orNull == "true"
                val enableLibsrt =
                    providers.gradleProperty("openstream.enableLibsrt").orNull?.toBooleanStrictOrNull()
                        ?: !nonStreamingCiBuild
                arguments += "-DOPENSTREAM_ENABLE_LIBSRT=${if (enableLibsrt) "ON" else "OFF"}"
                providers.gradleProperty("openstream.libsrtIncludeDir").orNull?.let {
                    arguments += "-DOPENSTREAM_LIBSRT_INCLUDE_DIR=$it"
                }
                providers.gradleProperty("openstream.libsrtLibrary").orNull?.let {
                    arguments += "-DOPENSTREAM_LIBSRT_LIBRARY=$it"
                }
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
