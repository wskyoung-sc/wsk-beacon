package com.beacon.testapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class BeaconScanViewModel(application: Application) : AndroidViewModel(application) {
    private val tracker = BeaconTracker.get(application)

    val uiState: StateFlow<ScanUiState> = tracker.uiState

    fun startScanning() {
        BeaconScanService.start(getApplication())
    }

    fun setAppForeground(foreground: Boolean) {
        tracker.setAppForeground(foreground)
    }

    fun setFloorOverride(code: String) {
        tracker.setFloorOverride(code)
    }
}
