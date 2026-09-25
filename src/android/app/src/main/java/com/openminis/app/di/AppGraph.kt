package com.openminis.app.di

/** Startup wiring boundary between Application and containerized repositories. */
object AppGraph {
    fun create(scopes: AppCoroutineScopes): AppContainer = AppContainer(scopes)
}
