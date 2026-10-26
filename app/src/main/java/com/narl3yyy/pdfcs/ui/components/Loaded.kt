package com.narl3yyy.pdfcs.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * A value loaded in the background: starts as [initial] (e.g. a cached bitmap) and, when that is
 * null, is replaced by [load]. Reloads when any of [keys] changes.
 */
@Composable
fun <T : Any> rememberLoaded(initial: T?, vararg keys: Any?, load: suspend () -> T?): State<T?> {
    val state: MutableState<T?> = remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(*keys) {
        if (state.value == null) state.value = load()
    }
    return state
}
