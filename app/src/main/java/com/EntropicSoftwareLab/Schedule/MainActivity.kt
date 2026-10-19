package com.EntropicSoftwareLab.Schedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.di.WidgetLaunch
import com.EntropicSoftwareLab.Schedule.widget.WidgetLaunchIntents
import com.EntropicSoftwareLab.Schedule.ui.navigation.AppNavHost
import com.EntropicSoftwareLab.Schedule.ui.screens.permissions.PermissionGateScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.permissions.hasCalendarPermissions
import com.EntropicSoftwareLab.Schedule.ui.theme.AppTheme
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.LocalDate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is light-only, so both bars always get the light (dark-icon) style regardless of
        // the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ScheduleApp).container
        readWidgetLaunch(intent, container)
        // A sync on every return to the front, and every 20s while on screen. See syncWhileVisible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.syncWhileVisible() }
        }

        setContent {
            AppTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = AppThemeTokens.colors.background,
                    ) {
                        val context = LocalContext.current
                        // Re-checked on recreation, so revoking access in system settings (which
                        // restarts the process) drops the user back to the gate.
                        var granted by remember { mutableStateOf(context.hasCalendarPermissions()) }
                        if (granted) {
                            // Warm the calendar list and palettes now, so tapping "+" later opens a
                            // form that is already filled in rather than one that fills itself in
                            // while the user waits. Here rather than in Application.onCreate for two
                            // reasons: the query throws without permission, and a provider read
                            // before the first frame is the cold-start cost AppContainer's laziness
                            // exists to avoid. warm() is idempotent, so recomposition is free.
                            LaunchedEffect(Unit) { container.calendarDirectory.warm() }
                            AppNavHost()
                        } else {
                            PermissionGateScreen(onGranted = { granted = true })
                        }
                    }
                }
            }
        }
    }


    /**
     * A widget tap that arrives while the app is already running.
     *
     * The intent is launched `SINGLE_TOP`, so Android delivers it here instead of recreating the
     * activity. `setIntent` matters as well as parking the launch: without it `getIntent()` keeps
     * returning the one the activity started with, and anything later reading it sees the wrong day.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readWidgetLaunch(intent, (application as ScheduleApp).container)
    }

    /**
     * Parks what the widget asked for; it is [AppNavHost] that acts on it.
     *
     * Nothing can navigate from here — the `NavHostController` is created inside the composition,
     * and on a cold start it does not exist yet. Worse, the permission gate may keep it from
     * existing until the user answers. See [AppContainer.pendingWidgetLaunch].
     */
    private fun readWidgetLaunch(intent: Intent?, container: AppContainer) {
        val epochDay = intent?.getLongExtra(WidgetLaunchIntents.EXTRA_FOCUS_DATE, NO_DATE)
            ?: return
        if (epochDay == NO_DATE) return
        container.pendingWidgetLaunch.value = WidgetLaunch(
            date = LocalDate.ofEpochDay(epochDay),
            addEvent = intent.getBooleanExtra(WidgetLaunchIntents.EXTRA_ADD_EVENT, false),
        )
    }

    private companion object {
        /** Long.MIN_VALUE rather than -1, so no real epoch day can collide with "absent". */
        const val NO_DATE = Long.MIN_VALUE
    }
}
