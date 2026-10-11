package local.signaldock.demo.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.signaldock.Diagnostics
import local.signaldock.demo.core.data.SignalDockClient

/** One immutable state. [error] keeps the last good [diagnostics] visible. */
data class DiagnosticsUiState(
    val loading: Boolean = false,
    val diagnostics: Diagnostics? = null,
    val error: String? = null,
)

sealed interface DiagnosticsIntent {
    /** The screen became visible. */
    data object ScreenEntered : DiagnosticsIntent
    data object RefreshClicked : DiagnosticsIntent
}

/** Reads `diagnostics()` on screen entry and on manual refresh only. No polling, no Flow. */
class DiagnosticsViewModel(private val client: SignalDockClient) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    fun onIntent(intent: DiagnosticsIntent) {
        when (intent) {
            DiagnosticsIntent.ScreenEntered, DiagnosticsIntent.RefreshClicked -> load()
        }
    }

    private fun load() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            try {
                _state.value = DiagnosticsUiState(diagnostics = client.diagnostics())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
