package com.EntropicSoftwareLab.Schedule

import android.app.Application
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import com.EntropicSoftwareLab.Schedule.widget.CalendarChangeWorker
import com.EntropicSoftwareLab.Schedule.widget.WidgetRefreshWorker

/**
 * Owns the single [AppContainer]. Manual DI: at this app's size (a calendar repository, a settings
 * store and one assistant) a DI framework would cost more than it saves.
 */
class ScheduleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Unique periodic work with KEEP, so this is a no-op once scheduled.
        WidgetRefreshWorker.schedule(this)
        // Redraws the widgets whenever anything changes the calendar, including a sync with the app
        // closed. KEEP, so a launch never drops a watch that is already armed.
        CalendarChangeWorker.watch(this)
    }
}
