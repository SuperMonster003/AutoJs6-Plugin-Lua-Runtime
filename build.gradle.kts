plugins {
    id("com.android.application") version System.getProperty("gradle.agp.version") apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
