plugins {
    `kotlin-dsl`
}

group = "com.openminis.buildlogic"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("minisKotlinLibrary") {
            id = "minis.kotlin.library"
            implementationClass = "MinisKotlinLibraryPlugin"
        }
    }
}
