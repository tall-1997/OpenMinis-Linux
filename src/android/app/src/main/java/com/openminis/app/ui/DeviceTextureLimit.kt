package com.openminis.app.ui

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay

/**
 * [T-android-canvas-large-bitmap-crash-followup] Queries the GPU's largest
 * texture dimension via EGL. The pbuffer max size is reported per-config
 * without a current GL context, so this runs on any thread (decode is on
 * Dispatchers.IO).
 *
 * Why it matters: Compose draws `Image(bitmap=…)` through a GPU texture when
 * the bitmap fits the GL max texture size, and falls back to the software
 * `RecordingCanvas` when it does not — that fallback is exactly where the
 * 322MB crash came from. Decoding up to the texture limit keeps every
 * decodable image on the GPU path, so a zoom viewer can keep near-source
 * detail on any device that can actually draw it.
 */
object DeviceTextureLimit {

    /**
     * 4096 is the max texture dimension essentially every GPU in our install
     * base supports — the safe floor when the probe fails.
     */
    private const val FALLBACK = 4096

    private val maxTextureEdge: Int by lazy { probe() }

    /** Longest edge a bitmap may have and still be GPU-drawable. */
    fun maxBitmapEdge(): Int = maxTextureEdge

    private fun probe(): Int = runCatching {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) return@runCatching FALLBACK
        val major = IntArray(1)
        val minor = IntArray(1)
        if (!EGL14.eglInitialize(display, major, 0, minor, 0)) return@runCatching FALLBACK
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return@runCatching FALLBACK
            }
            val value = IntArray(1)
            if (!EGL14.eglGetConfigAttrib(display, configs[0], EGL14.EGL_MAX_PBUFFER_WIDTH, value, 0)) {
                return@runCatching FALLBACK
            }
            value[0]
        } finally {
            EGL14.eglTerminate(display)
        }
    }.getOrDefault(FALLBACK).coerceAtLeast(FALLBACK)
}
