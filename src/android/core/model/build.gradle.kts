plugins {
    id("minis.kotlin.library")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Compile-only: org.json resolves from the Android platform at runtime.
    // Packaging the Maven artifact used to duplicate the bootclasspath copy
    // (the R8 -keep org.json.** rule in app/proguard-rules.pro existed for
    // that duplicate). :core:model is a plain JVM module (no android.jar on
    // its classpath), so it still compiles against the Maven 20231013 API —
    // which is a superset of the platform's — while the APK binds to the
    // platform classes only.
    compileOnly("org.json:json:20231013")
}
