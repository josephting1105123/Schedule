package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * The wall-clock time, re-read on every minute boundary and whenever the screen resumes.
 * The resume re-read matters: a coroutine delay runs on uptime, which stops while the phone
 * sleeps, so after a night asleep the minute tick alone would be hours late.
 */
@Composable
fun rememberLiveNow(): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val msToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L
            delay(msToNextMinute)
            now.value = LocalDateTime.now()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                now.value = LocalDateTime.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return now
}
