import java.io.File
import java.util.Properties

plugins {
    id("io.github.supermonster003.autojs6-native-alignment")
    id("com.android.application")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { stream -> load(stream) }
}

val runtimeModeDimension = "runtimeMode"
val supportedAbis = setOf("arm64-v8a", "x86_64")
val protocolArtifacts = listOf(
    rootProject.file("protocol/common-plugin-api.aar"),
    rootProject.file("protocol/protocol-wire-api.aar"),
    rootProject.file("protocol/lua-runtime-api.aar"),
)

data class ReleaseSigningMaterial(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun nonBlankSigningValue(properties: Properties, key: String): String =
    properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("External release signing properties are missing '$key'")

fun loadReleaseSigningMaterial(): ReleaseSigningMaterial? {
    val propertiesPath = providers.gradleProperty(
        "autojs.lua.release.signingPropertiesFile",
    ).orNull?.trim()?.takeIf(String::isNotEmpty)
    val externalStorePath = providers.gradleProperty(
        "autojs.lua.release.signingStoreFile",
    ).orNull?.trim()?.takeIf(String::isNotEmpty)
    val externalRequested = propertiesPath != null || externalStorePath != null

    val environmentValues = listOf(
        providers.environmentVariable("AUTOJS_LUA_RELEASE_STORE_FILE").orNull,
        providers.environmentVariable("AUTOJS_LUA_RELEASE_STORE_PASSWORD").orNull,
        providers.environmentVariable("AUTOJS_LUA_RELEASE_KEY_ALIAS").orNull,
        providers.environmentVariable("AUTOJS_LUA_RELEASE_KEY_PASSWORD").orNull,
    )
    val environmentRequested = environmentValues.any { !it.isNullOrBlank() }

    if (externalRequested && environmentRequested) {
        throw GradleException(
            "Choose either external release signing files or AUTOJS_LUA_RELEASE_* variables, not both",
        )
    }
    if (externalRequested) {
        val signingPropertiesFile = File(
            propertiesPath ?: throw GradleException(
                "External signing requires -Pautojs.lua.release.signingPropertiesFile=<absolute path>",
            ),
        )
        val signingStoreFile = File(
            externalStorePath ?: throw GradleException(
                "External signing requires -Pautojs.lua.release.signingStoreFile=<absolute path>",
            ),
        )
        if (!signingPropertiesFile.isAbsolute || !signingStoreFile.isAbsolute) {
            throw GradleException("External release signing paths must be absolute")
        }
        if (!signingPropertiesFile.isFile || !signingStoreFile.isFile) {
            throw GradleException("External release signing files are unavailable or not regular files")
        }
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use { stream -> load(stream) }
        }
        return ReleaseSigningMaterial(
            storeFile = signingStoreFile.canonicalFile,
            storePassword = nonBlankSigningValue(signingProperties, "storePassword"),
            keyAlias = nonBlankSigningValue(signingProperties, "keyAlias"),
            keyPassword = nonBlankSigningValue(signingProperties, "keyPassword"),
        )
    }

    if (!environmentRequested) return null
    if (environmentValues.any { it.isNullOrBlank() }) {
        throw GradleException("All four AUTOJS_LUA_RELEASE_* variables must be provided together")
    }
    return ReleaseSigningMaterial(
        storeFile = rootProject.file(checkNotNull(environmentValues[0])).canonicalFile,
        storePassword = checkNotNull(environmentValues[1]),
        keyAlias = checkNotNull(environmentValues[2]),
        keyPassword = checkNotNull(environmentValues[3]),
    )
}

val releaseSigningMaterial = loadReleaseSigningMaterial()

android {
    namespace = "io.github.supermonster003.autojs6.plugin.lua.runtime"
    compileSdk = versionProperties.getProperty("COMPILE_SDK_VERSION").toInt()
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.supermonster003.autojs6.plugin.lua.runtime"
        minSdk = versionProperties.getProperty("MIN_SDK_VERSION").toInt()
        targetSdk = versionProperties.getProperty("TARGET_SDK_VERSION").toInt()
        versionCode = versionProperties.getProperty("VERSION_BUILD").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "long",
            "REQUIRED_HOST_VERSION_CODE",
            "${versionProperties.getProperty("REQUIRED_HOST_VERSION_CODE")}L",
        )
        resValue(
            "string",
            "lua_runtime_requires_host_version",
            versionProperties.getProperty("REQUIRED_HOST_VERSION_CODE"),
        )

        ndk {
            abiFilters += supportedAbis
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DAUTOJS_LUA_RUNTIME_SLOT=lua54",
                    "-DANDROID_STL=c++_static",
                )
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        releaseSigningMaterial?.let { material ->
            create("release") {
                storeFile = material.storeFile
                storePassword = material.storePassword
                keyAlias = material.keyAlias
                keyPassword = material.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    flavorDimensions += runtimeModeDimension
    productFlavors {
        create("provider") {
            dimension = runtimeModeDimension
            externalNativeBuild {
                cmake {
                    arguments += "-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=OFF"
                }
            }
        }
        create("nativeTest") {
            dimension = runtimeModeDimension
            applicationIdSuffix = ".native_test"
            externalNativeBuild {
                cmake {
                    arguments += "-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=OFF"
                }
            }
        }
        create("faultTest") {
            dimension = runtimeModeDimension
            applicationIdSuffix = ".fault_test"
            externalNativeBuild {
                cmake {
                    arguments += "-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=ON"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    implementation(files(protocolArtifacts))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

tasks.register("verifyPinnedInputs") {
    group = "verification"
    doLast {
        val missingProtocol = protocolArtifacts.filterNot { it.isFile }
        check(missingProtocol.isEmpty()) {
            "Pinned protocol AARs are missing: ${missingProtocol.joinToString { it.name }}"
        }
        check(file("src/main/cpp/vendor/lua-5.4.8/src/lapi.c").isFile) {
            "Pinned PUC Lua 5.4.8 sources are not vendored"
        }
    }
}

tasks.matching { task ->
    task.name.startsWith("compile") || task.name.startsWith("assemble") || task.name.startsWith("bundle")
}.configureEach {
    dependsOn("verifyPinnedInputs")
}

tasks.register("verifyReleasePreconditions") {
    group = "verification"
    doLast {
        check(
            versionProperties.getProperty("VERSION_NAME")
                .matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+-rc\\.[0-9]+$")),
        ) {
            "Release candidates require a version name such as 0.1.0-rc.1"
        }
        if (releaseSigningMaterial == null) {
            logger.lifecycle(
                "providerRelease is unsigned; use tools/build_runnable_provider.ps1 with external signing material for a publishable artifact",
            )
        }
    }
}

val releaseArtifactTaskNames = setOf(
    "assembleProviderRelease",
    "bundleProviderRelease",
    "packageProviderRelease",
    "packageProviderReleaseBundle",
    "packageProviderReleaseUniversalApk",
)
tasks.matching { task -> task.name in releaseArtifactTaskNames }.configureEach {
    dependsOn("verifyReleasePreconditions")
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        val runtimeMode = variantBuilder.productFlavors
            .single { (dimension, _) -> dimension == runtimeModeDimension }
            .second
        if (runtimeMode != "provider") {
            variantBuilder.enable = false
        }
    }
}
