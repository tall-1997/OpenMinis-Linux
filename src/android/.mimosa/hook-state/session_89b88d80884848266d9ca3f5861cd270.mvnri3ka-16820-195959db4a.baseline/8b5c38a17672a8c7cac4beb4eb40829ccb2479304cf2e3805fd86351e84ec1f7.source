package com.openminis.app.sandbox

/** Reproducible guest build profiles; installation remains an explicit shell action. */
enum class BuildEnvironmentProfile(
    val id: String,
    val packages: List<String>,
    val commands: List<String>,
) {
    NATIVE(
        "native",
        listOf("build-essential", "pkg-config", "cmake", "ninja-build", "git"),
        listOf("make", "cmake", "ninja"),
    ),
    PYTHON(
        "python",
        listOf("python3", "python3-pip", "python3-venv", "python3-dev"),
        listOf("python3", "pip3"),
    ),
    GO(
        "go",
        listOf("golang-go"),
        listOf("go"),
    ),
    RUST(
        "rust",
        listOf("rustc", "cargo"),
        listOf("rustc", "cargo"),
    ),
    JAVA(
        "java",
        listOf("openjdk-17-jdk", "gradle"),
        listOf("java", "gradle"),
    ),
    ANDROID(
        "android",
        emptyList(),
        listOf("aapt2", "sdkmanager"),
    );

    fun setupCommand(action: String = "plan"): String {
        require(action == "plan" || action == "install")
        return "minis-build-env $id $action"
    }

    companion object {
        fun fromId(id: String): BuildEnvironmentProfile? = entries.firstOrNull { it.id == id }
        fun describe(): String = entries.joinToString("; ") { "${it.id}: ${it.packages.joinToString(",")}" }
    }
}
