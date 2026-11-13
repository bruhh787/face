package com.facesort.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facesort.app.ui.App
import com.facesort.app.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val hasMedia = mutableStateOf(false)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMedia.value = checkMedia()
    }
    private val writeLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onWriteResult(it.resultCode == RESULT_OK)
    }

    private fun checkMedia(): Boolean {
        fun g(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> g(Manifest.permission.READ_MEDIA_VIDEO) || g(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> g(Manifest.permission.READ_MEDIA_VIDEO)
            else -> g(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun requestPerms() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasMedia.value = checkMedia()
        if (!hasMedia.value) requestPerms()
        setContent {
            val chunk by vm.pendingChunk.collectAsStateWithLifecycle()
            LaunchedEffect(chunk) {
                val c = chunk ?: return@LaunchedEffect
                val pi = MediaStore.createWriteRequest(contentResolver, c.map { it.uri })
                writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
            App(vm, hasMedia.value, onRequestPerms = ::requestPerms)
        }
    }

    override fun onResume() { super.onResume(); hasMedia.value = checkMedia() }
}
