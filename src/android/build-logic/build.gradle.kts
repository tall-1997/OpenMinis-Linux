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
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
}

gradlePlugin {
    plugins {
        register("minisKotlinLibrary") {
            id = "minis.kotlin.library"
            implementationClass = "MinisKotlinLibraryPlugin"
        }
    }
}
