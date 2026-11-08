package com.nativeauto.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether precise location is granted, observable by the UI. Android has no permission
 * callback, so [refresh] is called after a permission dialog and whenever the app resumes
 * (the user may have changed it in system settings).
 */
interface LocationPermissionState {
    val granted: StateFlow<Boolean>
    fun refresh()
}

@Singleton
class AndroidLocationPermissionState @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationPermissionState {

    private val _granted = MutableStateFlow(check())
    override val granted: StateFlow<Boolean> = _granted.asStateFlow()

    override fun refresh() {
        _granted.value = check()
    }

    private fun check() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
