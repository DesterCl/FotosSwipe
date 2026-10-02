package com.fotosswipe.app

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Photo(val id: Long, val uri: Uri)

/** Tiempo de espera antes de borrar definitivamente: 5 minutos */
const val WAIT_MS = 5 * 60 * 1000L

class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val photos = MutableStateFlow<List<Photo>>(emptyList())
    val locked = MutableStateFlow(store.locked())
    val kept = MutableStateFlow(store.kept())
    val pending = MutableStateFlow(store.pending())
    val now = MutableStateFlow(System.currentTimeMillis())

    /** true mientras hay un diálogo de borrado del sistema abierto */
    var busy = false

    /** Fotos por revisar (sin las pendientes ni las ya conservadas) */
    val deck: StateFlow<List<Photo>> = combine(photos, pending, kept) { p, pen, k ->
        p.filter { it.id !in pen.keys && it.id !in k }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Fotos en lista de espera, las más antiguas primero */
    val pendingPhotos: StateFlow<List<Photo>> = combine(photos, pending) { p, pen ->
        p.filter { it.id in pen.keys }.sortedBy { pen[it.id] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Fotos cuyo tiempo de espera ya terminó y deben borrarse */
    val expired: StateFlow<List<Photo>> = combine(pendingPhotos, pending, now) { list, pen, n ->
        list.filter { n - (pen[it.id] ?: n) >= WAIT_MS }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                now.value = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<Photo>()
            val coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            getApplication<Application>().contentResolver.query(
                coll,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    list += Photo(id, ContentUris.withAppendedId(coll, id))
                }
            }
            val ids = list.map { it.id }.toSet()
            // limpiar datos de fotos que ya no existen
            pending.value = pending.value.filterKeys { it in ids }.also { store.savePending(it) }
            photos.value = list
        }
    }

    fun sendToPending(id: Long) {
        pending.value = pending.value + (id to System.currentTimeMillis())
        store.savePending(pending.value)
    }

    fun restore(ids: List<Long>) {
        pending.value = pending.value - ids.toSet()
        store.savePending(pending.value)
    }

    fun restoreAll() = restore(pending.value.keys.toList())

    fun onDeleted(ids: List<Long>) {
        restore(ids)
        photos.value = photos.value.filter { it.id !in ids }
    }

    fun keep(id: Long) {
        kept.value = kept.value + id
        store.saveKept(kept.value)
    }

    fun resetKept() {
        kept.value = emptySet()
        store.saveKept(kept.value)
    }

    fun toggleLock(id: Long) {
        locked.value = if (id in locked.value) locked.value - id else locked.value + id
        store.saveLocked(locked.value)
    }
}
