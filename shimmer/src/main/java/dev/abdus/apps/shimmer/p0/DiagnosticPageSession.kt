package dev.abdus.apps.shimmer.p0

import androidx.lifecycle.ViewModel

/** Retains one immutable export across a configuration change, never resamples after the picker. */
internal class DiagnosticPageSession : ViewModel() {
    data class Export(val fileName: String, val content: String, val token: DiagnosticToken)
    private var pending: Export? = null
    private var displayGeneration = 0L

    fun beginExport(snapshot: DiagnosticSnapshot): Export? {
        if (pending != null) return null
        return Export(snapshot.fileName, snapshot.content, snapshot.token).also { pending = it }
    }

    fun takeExport(): Export? = pending.also { pending = null }
    fun hasPendingExport(): Boolean = pending != null
    fun invalidateDisplay(): Long = ++displayGeneration
    fun displayVersion(): Long = displayGeneration
    fun acceptsDisplay(version: Long, token: DiagnosticToken): Boolean =
        version == displayGeneration && P0Diagnostics.isCurrent(token)
}
