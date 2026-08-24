import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { stream -> load(stream) }
}

data class HostLifecycleSigningMaterial(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun nonBlankSigningValue(properties: Properties, key: String): String =
    properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Host lifecycle signing properties are missing '$key'")

fun loadHostLifecycleSigningMaterial(): HostLifecycleSigningMaterial? {
    val propertiesPath = providers.gradleProperty(
        "autojs.lua.hostLifecycle.signingPropertiesFile",
    ).orNull?.trim()?.takeIf(String::isNotEmpty)
    val storePath = providers.gradleProperty(
        "autojs.lua.hostLifecycle.signingStoreFile",
    ).orNull?.trim()?.takeIf(String::isNotEmpty)
    if (propertiesPath == null && storePath == null) return null
    if (propertiesPath == null || storePath == null) {
        throw GradleException("Host lifecycle signing requires both external signing paths")
    }
    val propertiesFile = File(propertiesPath)
    val storeFile = File(storePath)
    if (!propertiesFile.isAbsolute || !storeFile.isAbsolute) {
        throw GradleException("Host lifecycle signing paths must be absolute")
    }
    if (!propertiesFile.isFile || !storeFile.isFile) {
        throw GradleException("Host lifecycle signing files are unavailable or not regular files")
    }
    val signingProperties = Properties().apply {
        propertiesFile.inputStream().use { stream -> load(stream) }
    }
    return HostLifecycleSigningMaterial(
        storeFile = storeFile.canonicalFile,
        storePassword = nonBlankSigningValue(signingProperties, "storePassword"),
        keyAlias = nonBlankSigningValue(signingProperties, "keyAlias"),
        keyPassword = nonBlankSigningValue(signingProperties, "keyPassword"),
    )
}

val hostLifecycleSigningMaterial = loadHostLifecycleSigningMaterial()

android {
    namespace = "io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test"
    compileSdk = versionProperties.getProperty("COMPILE_SDK_VERSION").toInt()

    defaultConfig {
        applicationId = "io.github.supermonster003.autojs6.plugin.lua.runtime.host.lifecycle.test"
        minSdk = versionProperties.getProperty("MIN_SDK_VERSION").toInt()
        targetSdk = versionProperties.getProperty("TARGET_SDK_VERSION").toInt()
        versionCode = versionProperties.getProperty("VERSION_BUILD").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        hostLifecycleSigningMaterial?.let { material ->
            create("hostLifecycle") {
                storeFile = material.storeFile
                storePassword = material.storePassword
                keyAlias = material.keyAlias
                keyPassword = material.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("hostLifecycle")?.let { signingConfig = it }
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    implementation(files(
        rootProject.file("protocol/common-plugin-api.aar"),
        rootProject.file("protocol/protocol-wire-api.aar"),
        rootProject.file("protocol/lua-runtime-api.aar"),
    ))
}
