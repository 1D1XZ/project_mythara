package com.mythara.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.SettingsStore
import com.mythara.minimax.ApiException
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.Region
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
) : ViewModel() {

    data class State(
        val region: Region = Region.Default,
        val apiKey: String? = null,
        val model: String = SettingsStore.DEFAULT_MODEL,
        val validating: Boolean = false,
        val validation: ValidationResult? = null,
        val supportedModels: List<String> = SettingsStore.SUPPORTED_MODELS,
    )

    data class ValidationResult(val ok: Boolean, val message: String)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = store.snapshot()
            _state.update { it.copy(region = snap.region, apiKey = snap.apiKey, model = snap.model) }
        }
    }

    suspend fun setRegion(r: Region) {
        store.setRegion(r)
        _state.update { it.copy(region = r, validation = null) }
    }

    suspend fun setModel(m: String) {
        store.setModel(m)
        _state.update { it.copy(model = m) }
    }

    suspend fun saveAndValidate(plainKey: String) {
        if (plainKey.isBlank()) return
        store.setApiKey(plainKey)
        _state.update { it.copy(apiKey = plainKey, validating = true, validation = null) }
        val region = _state.value.region
        val client = MiniMaxClient(apiKey = plainKey, region = region)
        val res = runCatching { client.validateKey().getOrThrow() }
        val v = when {
            res.isSuccess -> ValidationResult(ok = true, message = "key OK · ${res.getOrNull()?.size ?: 0} models visible")
            res.exceptionOrNull() is ApiException ->
                ValidationResult(ok = false, message = (res.exceptionOrNull() as ApiException).mapped.message)
            else ->
                ValidationResult(ok = false, message = res.exceptionOrNull()?.message ?: "validation failed")
        }
        _state.update { it.copy(validating = false, validation = v) }
    }
}
