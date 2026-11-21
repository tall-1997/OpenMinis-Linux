pluginManagement {
    repositories {
        // CN mirrors first — build-logic is an included build and does NOT
        // inherit the root settings.gradle.kts mirror logic. Without these it
        // resolves plugins against google()/mavenCentral()/gradlePluginPortal()
        // which are unreachable or slow from mainland CI.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
