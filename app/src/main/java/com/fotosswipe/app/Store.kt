package com.fotosswipe.app

import android.content.Context

/** Guarda en el teléfono: fotos bloqueadas, conservadas y en lista de espera. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fotos_swipe", Context.MODE_PRIVATE)

    private fun longs(key: String): Set<Long> =
        (sp.getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    private fun saveLongs(key: String, s: Set<Long>) {
        sp.edit().putStringSet(key, s.map { it.toString() }.toSet()).apply()
    }

    fun locked(): Set<Long> = longs("locked")
    fun saveLocked(s: Set<Long>) = saveLongs("locked", s)

    fun kept(): Set<Long> = longs("kept")
    fun saveKept(s: Set<Long>) = saveLongs("kept", s)

    /** id de foto -> momento (ms) en que se envió a la lista de espera */
    fun pending(): Map<Long, Long> =
        (sp.getStringSet("pending", emptySet()) ?: emptySet()).mapNotNull {
            val p = it.split(":")
            val id = p.getOrNull(0)?.toLongOrNull()
            val t = p.getOrNull(1)?.toLongOrNull()
            if (id != null && t != null) id to t else null
        }.toMap()

    fun savePending(m: Map<Long, Long>) {
        sp.edit().putStringSet("pending", m.map { "${it.key}:${it.value}" }.toSet()).apply()
    }
}
