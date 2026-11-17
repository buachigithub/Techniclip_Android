package com.decio.skipii

import androidx.compose.runtime.Composable

/** App updates are distributed via Google Play Store; in-app APK self-updater is disabled. */
object AppUpdate {
    val available: Any? = null
    const val checking: Boolean = false
}

@Composable
fun UpdateAction() = Unit
