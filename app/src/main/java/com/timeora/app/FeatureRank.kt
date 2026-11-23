package com.timevora.app

import android.content.Context

/** Usage counts + optional manual order for Home / More feature lists. */
object FeatureRank {
    private const val PREFS = "timevora_feature_rank"
    private const val KEY_ORDER = "manual_order"
    private const val KEY_CUSTOM = "custom_mode"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordOpen(c: Context, id: String) {
        if (id.isBlank()) return
        val p = prefs(c)
        p.edit().putInt("use_$id", p.getInt("use_$id", 0) + 1).apply()
    }

    fun useCount(c: Context, id: String): Int = prefs(c).getInt("use_$id", 0)

    fun isCustom(c: Context): Boolean = prefs(c).getBoolean(KEY_CUSTOM, false)

    fun setCustomOrder(c: Context, ids: List<String>) {
        prefs(c).edit()
            .putString(KEY_ORDER, ids.joinToString("|"))
            .putBoolean(KEY_CUSTOM, true)
            .apply()
    }

    fun clearCustom(c: Context) {
        prefs(c).edit().putBoolean(KEY_CUSTOM, false).remove(KEY_ORDER).apply()
    }

    fun manualOrder(c: Context): List<String> {
        val s = prefs(c).getString(KEY_ORDER, "") ?: ""
        return s.split("|").filter { it.isNotBlank() }
    }

    fun <T> sort(c: Context, items: List<T>, idOf: (T) -> String): List<T> {
        if (items.isEmpty()) return items
        if (isCustom(c)) {
            val order = manualOrder(c)
            val idx = order.withIndex().associate { it.value to it.index }
            return items.sortedBy { idx[idOf(it)] ?: (1000 + items.indexOf(it)) }
        }
        return items.sortedWith(
            compareByDescending<T> { useCount(c, idOf(it)) }
                .thenBy { items.indexOf(it) }
        )
    }

    fun move(c: Context, ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val m = ids.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        setCustomOrder(c, m)
        return m
    }
}
