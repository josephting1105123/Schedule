package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime

private const val NO_KEY_REASON = "Add a Gemini key in Settings to use the assistant."

/**
 * Sends each request to the cloud when a Gemini key is set, and refuses with [AssistantState.NoKey]
 * otherwise.
 *
 * This is the app's actual [ScheduleAssistant] — [AppContainer][com.EntropicSoftwareLab.Schedule.di.AppContainer]
 * wires this in. The key **and the model id** are read per request, so editing either in Settings
 * switches models with no restart.
 */
class RoutingAssistant(
    private val settings: SettingsRepository,
    private val cloudFactory: (apiKey: String, model: String) -> ScheduleAssistant,
) : ScheduleAssistant {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val apiKey: Flow<String?> =
        settings.geminiApiKey.map { it?.takeIf { key -> key.isNotBlank() } }.distinctUntilChanged()

    override val state: StateFlow<AssistantState> =
        apiKey.map { key -> if (key != null) AssistantState.Ready else AssistantState.NoKey }
            .stateIn(scope, SharingStarted.Eagerly, AssistantState.Loading)

    override suspend fun parseEvents(
        text: String,
        now: LocalDateTime,
        attachments: List<Attachment>,
    ): AssistantResult<List<EventDraft>> = active()?.parseEvents(text, now, attachments)
        ?: AssistantResult.Unavailable(NO_KEY_REASON)

    override suspend fun parseQuery(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<AvailabilityQuery> = active()?.parseQuery(text, now)
        ?: AssistantResult.Unavailable(NO_KEY_REASON)

    override suspend fun parseCancellation(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<CancellationQuery> = active()?.parseCancellation(text, now)
        ?: AssistantResult.Unavailable(NO_KEY_REASON)

    override suspend fun answerQuestion(
        text: String,
        now: LocalDateTime,
    ): AssistantResult<GeneralAnswer> = active()?.answerQuestion(text, now)
        ?: AssistantResult.Unavailable(NO_KEY_REASON)

    /** Nothing to warm without a key — there is no local model to preload anymore. */
    override suspend fun warmUp() {}

    /** The cloud client is stateless; there is nothing to release. */
    override fun close() {}

    private suspend fun active(): ScheduleAssistant? {
        val key = apiKey.first() ?: return null
        return cloudFactory(key, settings.geminiModel.first())
    }
}
