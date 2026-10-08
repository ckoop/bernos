package de.bernos.app.bluetooth

/**
 * Entscheidet, wann vor einem fast leeren Kopfhörer-Akku gewarnt wird: einmal, sobald der Stand
 * unter [threshold] fällt, und erst wieder, nachdem er auf mindestens [resetAt] geladen wurde.
 * So meldet sich Bernos nicht bei jedem Schwanken zwischen 14 und 15 %.
 */
class LowBatteryAlert(private val threshold: Int = 15, private val resetAt: Int = 20) {
    private var warned = false

    sealed interface Action {
        data class Warn(val level: Int) : Action
        data object Clear : Action
        data object None : Action
    }

    /** [level] = `null`, wenn der Kopfhörer getrennt ist; die Warnung bleibt dann bestehen. */
    fun update(level: Int?): Action {
        if (level == null) return Action.None
        return when {
            level < threshold && !warned -> {
                warned = true
                Action.Warn(level)
            }
            level >= resetAt && warned -> {
                warned = false
                Action.Clear
            }
            else -> Action.None
        }
    }
}
