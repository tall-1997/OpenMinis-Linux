plugins {
    id("minis.kotlin.library")
    kotlin("plugin.serialization")
}

dependencies {
    // [T-p0-1-extraction] 纯 agent 逻辑（中断尾检测 / 工具循环检测 / 消息变换）从
    // :app 迁入后需要核心消息模型；org.json 是 core:model 的 implementation 依赖
    // 不传递，ToolLoopDetector 直接用 JSONArray/JSONObject，故此处自行声明。
    implementation(project(":core:model"))
    implementation("org.json:json:20231013")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
