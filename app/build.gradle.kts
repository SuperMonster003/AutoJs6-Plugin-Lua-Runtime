import java.util.Properties

plugins {
    id("com.android.application")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { stream -> load(stream) }
}

fun flag(name: String) = providers.gradleProperty(name)
    .orElse("false")
    .map { value -> value.trim().lowercase() in setOf("true", "1", "yes", "on") }

val luaNativeEnabled = flag("autojs.lua.native.enabled")
val luaProviderEnabled = flag("autojs.lua.provider.enabled")
val luaFaultHarnessEnabled = flag("autojs.lua.faultHarness.enabled")
val supportedAbis = setOf("arm64-v8a", "x86_64")
val protocolArtifacts = listOf(
    rootProject.file("protocol/common-plugin-api.aar"),
    rootProject.file("protocol/protocol-wire-api.aar"),
    rootProject.file("protocol/lua-runtime-api.aar"),
)

if (luaProviderEnabled.get() && !luaNativeEnabled.get()) {
    throw GradleException("The Lua provider cannot be enabled without the pinned native runtime")
}
if (luaFaultHarnessEnabled.get() && (!luaNativeEnabled.get() || luaProviderEnabled.get())) {
    throw GradleException(
        "The debug Lua fault harness requires native=true and provider=false",
    )
}

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

        buildConfigField("boolean", "LUA_NATIVE_ENABLED", luaNativeEnabled.get().toString())
        buildConfigField("boolean", "LUA_PROVIDER_ENABLED", luaProviderEnabled.get().toString())
        buildConfigField(
            "long",
            "REQUIRED_HOST_VERSION_CODE",
            "${versionProperties.getProperty("REQUIRED_HOST_VERSION_CODE")}L",
        )
        resValue("bool", "lua_runtime_provider_enabled", luaProviderEnabled.get().toString())

        ndk {
            abiFilters += supportedAbis
        }

        if (luaNativeEnabled.get()) {
            externalNativeBuild {
                cmake {
                    arguments += "-DAUTOJS_LUA_RUNTIME_SLOT=lua54"
                    cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
                }
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
        val storePath = providers.environmentVariable("AUTOJS_LUA_RELEASE_STORE_FILE").orNull
        val storePasswordValue = providers.environmentVariable("AUTOJS_LUA_RELEASE_STORE_PASSWORD").orNull
        val keyAliasValue = providers.environmentVariable("AUTOJS_LUA_RELEASE_KEY_ALIAS").orNull
        val keyPasswordValue = providers.environmentVariable("AUTOJS_LUA_RELEASE_KEY_PASSWORD").orNull
        if (listOf(storePath, storePasswordValue, keyAliasValue, keyPasswordValue).all { it != null }) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(storePath))
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField(
                "boolean",
                "LUA_FAULT_HARNESS_ENABLED",
                luaFaultHarnessEnabled.get().toString(),
            )
            resValue(
                "bool",
                "lua_runtime_fault_harness_enabled",
                luaFaultHarnessEnabled.get().toString(),
            )
            if (luaNativeEnabled.get()) {
                externalNativeBuild {
                    cmake {
                        arguments += if (luaFaultHarnessEnabled.get()) {
                            "-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=ON"
                        } else {
                            "-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=OFF"
                        }
                    }
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "LUA_FAULT_HARNESS_ENABLED", "false")
            resValue("bool", "lua_runtime_fault_harness_enabled", "false")
            if (luaNativeEnabled.get()) {
                externalNativeBuild {
                    cmake {
                        arguments += "-DAUTOJS_LUA_DEBUG_FAULT_HARNESS=OFF"
                    }
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    if (luaNativeEnabled.get()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
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
        if (luaNativeEnabled.get()) {
            check(file("src/main/cpp/vendor/lua-5.4.8/src/lapi.c").isFile) {
                "Pinned PUC Lua 5.4.8 sources are not vendored"
            }
        }
    }
}

tasks.matching { task ->
    task.name.startsWith("compile") || task.name.startsWith("assemble") || task.name.startsWith("bundle")
}.configureEach {
    dependsOn("verifyPinnedInputs")
}

tasks.register("requireReleaseSigning") {
    group = "verification"
    doLast {
        check(android.signingConfigs.findByName("release") != null) {
            "Release signing requires the four AUTOJS_LUA_RELEASE_* environment variables"
        }
    }
}

tasks.matching { task ->
    task.name.contains("Release") && (task.name.startsWith("assemble") || task.name.startsWith("bundle"))
}.configureEach {
    dependsOn("requireReleaseSigning")
}
