package com.fotosswipe.app

import android.app.RecoverableSecurityException
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: PhotoViewModel by viewModels()

    private var inFlight: List<Long> = emptyList()          // Android 11+
    private var legacyQueue: MutableList<Photo> = mutableListOf() // Android 10 o menos

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (Build.VERSION.SDK_INT >= 30) {
            if (result.resultCode == RESULT_OK) vm.onDeleted(inFlight) else vm.restore(inFlight)
            inFlight = emptyList()
            vm.busy = false
        } else {
            if (result.resultCode != RESULT_OK && legacyQueue.isNotEmpty()) {
                vm.restore(listOf(legacyQueue.removeAt(0).id))
            }
            legacyDelete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(vm) }

        // Cuando una foto cumple 5 min en espera, se pide borrarla definitivamente
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.expired.collect { list ->
                    if (list.isNotEmpty() && !vm.busy) startDeletion(list)
                }
            }
        }
    }

    private fun startDeletion(list: List<Photo>) {
        vm.busy = true
        if (Build.VERSION.SDK_INT >= 30) {
            inFlight = list.map { it.id }
            try {
                val sender = MediaStore.createDeleteRequest(contentResolver, list.map { it.uri }).intentSender
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (e: Exception) {
                vm.restore(inFlight)
                inFlight = emptyList()
                vm.busy = false
            }
        } else {
            legacyQueue = list.toMutableList()
            legacyDelete()
        }
    }

    private fun legacyDelete() {
        while (legacyQueue.isNotEmpty()) {
            val p = legacyQueue.first()
            try {
                val n = contentResolver.delete(p.uri, null, null)
                if (n > 0) vm.onDeleted(listOf(p.id)) else vm.restore(listOf(p.id))
                legacyQueue.removeAt(0)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    deleteLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                    return // se reintenta al volver el resultado
                } else {
                    vm.restore(listOf(p.id))
                    legacyQueue.removeAt(0)
                }
            }
        }
        vm.busy = false
    }
}
