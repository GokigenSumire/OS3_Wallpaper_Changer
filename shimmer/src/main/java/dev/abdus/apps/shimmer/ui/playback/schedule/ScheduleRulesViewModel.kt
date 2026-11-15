package dev.abdus.apps.shimmer.ui.playback.schedule

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.p4.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScheduleRulesViewModel(application: Application, val target: WallpaperTarget,
    private val sourcesProvider: (() -> Flow<List<P1SourceEntity>>)? = null) : AndroidViewModel(application) {
    private val store = ScheduleStore(application, target)
    private val editor = ScheduleEditor(store)
    private val state = MutableStateFlow(ScheduleRulesState(settings = store.loadSettings()))
    val uiState = state.asStateFlow()
    private var sourceJob: Job? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScheduleStore.KEY_SETTINGS) state.value = state.value.copy(settings = store.loadSettings())
    }
    init {
        store.registerListener(listener)
        retrySources()
    }
    fun retrySources() {
        sourceJob?.cancel()
        state.value = state.value.copy(sourcesLoading = true, sourceError = null)
        sourceJob = viewModelScope.launch {
            try {
                val sources = sourcesProvider?.invoke() ?:
                    ShimmerDatabase.getInstance(getApplication(), target).p1SourceDao().sourcesFlow()
                sources.collect {
                    state.value = state.value.copy(sources = it, sourcesLoading = false, sourceError = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(sourcesLoading = false, sourceError = "来源读取失败，请返回后重试") }
        }
    }
    fun saveDaily(id: String?, minute: Int): String? = edit { editor.saveDaily(id, minute) }
    fun saveRule(form: RuleForm, existing: Boolean): String? {
        if (state.value.sourcesLoading || state.value.sourceError != null) return "来源尚未就绪，请稍后再试"
        return edit { editor.saveRule(form, existing, state.value.sources.map { it.id }.toSet()) }
    }
    private fun edit(action: () -> String?): String? {
        val error = action()
        state.value = state.value.copy(settings = store.loadSettings())
        return error
    }
    fun setDailyEnabled(id: String, enabled: Boolean) { editor.setDailyEnabled(id, enabled); refresh() }
    fun setRuleEnabled(id: String, enabled: Boolean) { editor.setRuleEnabled(id, enabled); refresh() }
    fun deleteDaily(id: String) { editor.deleteDaily(id); refresh() }
    fun deleteRule(id: String) { editor.deleteRule(id); refresh() }
    fun moveRule(id: String, delta: Int) { editor.moveRule(id, delta); refresh() }
    fun refresh() {
        state.value = state.value.copy(settings = store.loadSettings())
        if (state.value.sourceError != null) retrySources()
    }
    override fun onCleared() { store.unregisterListener(listener); super.onCleared() }
}

data class ScheduleRulesState(
    val settings: ScheduleSettings = ScheduleSettings(),
    val sources: List<P1SourceEntity> = emptyList(),
    val sourcesLoading: Boolean = true,
    val sourceError: String? = null,
)
