package com.peaceantz.stagescope.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaceantz.stagescope.AppContainer
import com.peaceantz.stagescope.data.AppSettings
import com.peaceantz.stagescope.data.AppTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDemoMode(enabled) }
    }

    fun clearCalibration() {
        viewModelScope.launch { container.settingsRepository.setCalibration(null) }
    }

    fun setDimAppearance(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDimAppearance(enabled) }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { container.settingsRepository.setTheme(theme) }
    }
}
