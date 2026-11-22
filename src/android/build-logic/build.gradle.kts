plugins {
    `kotlin-dsl`
}

group = "com.openminis.buildlogic"

repositories {
    // [T-buildlogic-cn-mirrors] CN mirrors first: this included build resolves
    // its OWN dependencies (kotlin-gradle-plugin etc., incl. the embedded
    // Kotlin 2.0.21 that `kotlin-dsl` pulls in) against this block. Direct
    // google()/mavenCentral() are unreachable from mainland CI, and when the
    // embedded-Kotlin resolution silently fails the build dies with
    // NoClassDefFoundError: KotlinPluginWrapper. Mirror order matches the
    // pluginManagement block in build-logic/settings.gradle.kts.
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
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
