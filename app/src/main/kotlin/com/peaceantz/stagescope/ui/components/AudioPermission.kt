package com.peaceantz.stagescope.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a function that, when invoked (from an intentional Start action), checks for
 * RECORD_AUDIO and either proceeds immediately or requests it -- calling [onGranted] only once
 * permission is actually confirmed. Denial is left to the caller's existing capture status
 * handling (the engine reports [com.peaceantz.stagescope.audio.CaptureStatus.PermissionDenied]).
 */
@Composable
fun rememberAudioPermissionRequester(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    return remember(context) {
        {
            val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) onGranted() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
