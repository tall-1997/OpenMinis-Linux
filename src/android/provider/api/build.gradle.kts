plugins {
    id("minis.kotlin.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
