package com.EntropicSoftwareLab.Schedule.data.accounts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Its own DataStore file, for the same reason birthdays have one: every `edit` rewrites the file.
 * Every calendar read waits on this, so a corrupt file is replaced with an empty one rather than
 * failing all of them.
 */
private val Context.accountStore: DataStore<Preferences> by preferencesDataStore(
    name = "accounts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Which phone accounts the app uses, their nicknames, and what is ticked. Each write is one `edit`
 * that decodes the blob, changes it and encodes it back, so two writes can't lose each other.
 */
class AccountPrefsRepository(private val context: Context) {

    private object Keys {
        val PREFS = stringPreferencesKey("prefs")
    }

    val prefs: Flow<AccountPrefs> =
        context.accountStore.data
            .map { AccountPrefsCodec.decode(it[Keys.PREFS]) }
            .catch { emit(AccountPrefs()) }

    private suspend fun update(change: (AccountPrefs) -> AccountPrefs) {
        context.accountStore.edit { store ->
            store[Keys.PREFS] = AccountPrefsCodec.encode(change(AccountPrefsCodec.decode(store[Keys.PREFS])))
        }
    }

    /** No-op when the account is already added. */
    suspend fun addAccount(name: String, type: String) = update { p ->
        if (p.added.any { it.name == name }) p else p.copy(added = p.added + AddedAccount(name, type))
    }

    /** Also drops it from the unticked accounts, so adding it again starts ticked. */
    suspend fun removeAccount(name: String) = update { p ->
        p.copy(added = p.added.filterNot { it.name == name }, hiddenAccounts = p.hiddenAccounts - name)
    }

    suspend fun setNickname(name: String, nickname: String) = update { p ->
        p.copy(added = p.added.map { if (it.name == name) it.copy(nickname = nickname.trim()) else it })
    }

    suspend fun setAccountShown(name: String, shown: Boolean) = update { p ->
        p.copy(hiddenAccounts = if (shown) p.hiddenAccounts - name else p.hiddenAccounts + name)
    }

    suspend fun setCalendarShown(id: Long, shown: Boolean) = update { p ->
        p.copy(hiddenCalendarIds = if (shown) p.hiddenCalendarIds - id else p.hiddenCalendarIds + id)
    }

    suspend fun setBirthdaysShown(shown: Boolean) = update { it.copy(birthdaysShown = shown) }

    suspend fun hideBirthday(key: String) = update { it.copy(hiddenBirthdayKeys = it.hiddenBirthdayKeys + key) }

    suspend fun clearHiddenBirthdays() = update { it.copy(hiddenBirthdayKeys = emptySet()) }
}
