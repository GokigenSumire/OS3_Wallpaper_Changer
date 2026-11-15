package dev.abdus.apps.shimmer.ui.playback

enum class PlayOrderMode(val label: String) { NAME("名称"), TIME("时间"), RANDOM("随机"), MANUAL("手动") }

/** Stateless selectors: every chip directly saves a real order, with no Apply step. */
data class PlayOrderControls(val mode: PlayOrderMode, val folders: Boolean = false,
                             val creation: Boolean = true, val descending: Boolean = false) {
    fun order(): PlayOrder = when (mode) {
        PlayOrderMode.RANDOM -> PlayOrder.RANDOM
        PlayOrderMode.MANUAL -> PlayOrder.MANUAL
        PlayOrderMode.NAME -> if (descending) PlayOrder.NATURAL_DESC else PlayOrder.NATURAL_ASC
        PlayOrderMode.TIME -> PlayOrder.valueOf(
            "${if (folders) "FOLDER" else "IMAGE"}_${if (creation) "CREATED" else "MODIFIED"}_${if (descending) "DESC" else "ASC"}")
    }

    companion object {
        fun from(order: PlayOrder): PlayOrderControls = PlayOrderControls(
            when (order) {
                PlayOrder.RANDOM -> PlayOrderMode.RANDOM
                PlayOrder.MANUAL -> PlayOrderMode.MANUAL
                PlayOrder.NATURAL_ASC, PlayOrder.NATURAL_DESC -> PlayOrderMode.NAME
                else -> PlayOrderMode.TIME
            }, order.name.startsWith("FOLDER_"), !order.name.contains("_MODIFIED_"), order.name.endsWith("_DESC"))
    }
}
