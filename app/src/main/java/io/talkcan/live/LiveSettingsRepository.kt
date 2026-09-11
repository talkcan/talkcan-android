package io.talkcan.live

import android.content.SharedPreferences
import io.talkcan.secret.PreparedSecretMutation
import io.talkcan.secret.ProtectedSecretReference
import io.talkcan.secret.ProtectedSecretResult
import io.talkcan.secret.ProtectedSecretStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stores only the SOS target and an opaque reference outside the encrypted secret store. */
internal class LiveSettingsRepository(
    private val preferences: SharedPreferences,
    private val secrets: ProtectedSecretStore,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    suspend fun save(apiKey: String, sosChannelId: String?) = mutex.withLock {
        mutableState.value = state.value.copy(saving = true, error = null)
        val error = withContext(Dispatchers.IO) {
            if (sosChannelId != null && (sosChannelId.isBlank() || sosChannelId.length > 256)) {
                return@withContext "Invalid SOS channel."
            }
            if (apiKey.isNotEmpty() && (apiKey.length > 4_096 || apiKey.any { it <= ' ' || it >= '\u007f' })) {
                return@withContext "Enter an API key without whitespace."
            }
            val oldReference = reference()
            val nextReference = if (apiKey.isEmpty()) oldReference else ProtectedSecretReference(UUID.randomUUID().toString())
            val mutation: PreparedSecretMutation? = if (apiKey.isEmpty()) null else {
                val result = if (oldReference == null) secrets.prepareCreate(checkNotNull(nextReference), apiKey)
                else secrets.prepareReplace(oldReference, checkNotNull(nextReference), apiKey)
                when (result) {
                    is ProtectedSecretResult.Failure -> return@withContext result.error.message
                    is ProtectedSecretResult.Success -> result.value
                }
            }
            val committed = preferences.edit()
                .putString(KEY_REFERENCE, nextReference?.token)
                .putString(SOS_CHANNEL, sosChannelId)
                .commit()
            if (!committed) {
                mutation?.rollback()
                return@withContext "Could not save GPT-Live settings."
            }
            when (val finalization = mutation?.commit()) {
                is ProtectedSecretResult.Failure -> finalization.error.message
                else -> null
            }
        }
        mutableState.value = withContext(Dispatchers.IO) { snapshot(error) }
    }

    suspend fun clearKey() = mutex.withLock {
        mutableState.value = state.value.copy(saving = true, error = null)
        val error = withContext(Dispatchers.IO) {
            val reference = reference() ?: return@withContext null
            val mutation = when (val prepared = secrets.prepareDelete(reference)) {
                is ProtectedSecretResult.Failure -> return@withContext prepared.error.message
                is ProtectedSecretResult.Success -> prepared.value
            }
            if (!preferences.edit().remove(KEY_REFERENCE).commit()) {
                mutation.rollback()
                return@withContext "Could not clear the API key."
            }
            when (val result = mutation.commit()) {
                is ProtectedSecretResult.Failure -> result.error.message
                is ProtectedSecretResult.Success -> null
            }
        }
        mutableState.value = withContext(Dispatchers.IO) { snapshot(error) }
    }

    suspend fun <T> useKey(block: (String) -> T): ProtectedSecretResult<T>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            reference()?.let { secrets.use(it) { key -> block(key.toString()) } }
        }
    }

    private fun reference(): ProtectedSecretReference? = preferences.getString(KEY_REFERENCE, null)
        ?.takeIf { it.isNotBlank() }?.let(::ProtectedSecretReference)

    private fun snapshot(error: String? = null) = LiveSettingsState(
        keyConfigured = reference()?.let(secrets::contains) == true,
        sosChannelId = preferences.getString(SOS_CHANNEL, null),
        error = error,
    )

    private companion object {
        const val KEY_REFERENCE = "key_reference"
        const val SOS_CHANNEL = "sos_channel"
    }
}
