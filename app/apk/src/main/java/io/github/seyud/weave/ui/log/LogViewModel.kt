package io.github.seyud.weave.ui.log

import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.seyud.weave.arch.AsyncLoadViewModel
import io.github.seyud.weave.core.BuildConfig
import io.github.seyud.weave.core.Info
import io.github.seyud.weave.core.R
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import io.github.seyud.weave.core.ktx.await
import io.github.seyud.weave.core.ktx.timeFormatStandard
import io.github.seyud.weave.core.ktx.toTime
import io.github.seyud.weave.core.model.su.SuLog
import io.github.seyud.weave.core.repository.LogRepository
import io.github.seyud.weave.core.utils.MediaStoreUtils
import io.github.seyud.weave.core.utils.MediaStoreUtils.outputStream
import io.github.seyud.weave.utils.TextHolder
import io.github.seyud.weave.utils.asText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import java.io.FileInputStream

class LogViewModel(
    private val repo: LogRepository
) : AsyncLoadViewModel() {

    sealed interface LogEvent {
        data class ShowSnackbar(
            val message: TextHolder,
            val duration: SnackbarDuration = SnackbarDuration.Short,
        ) : LogEvent
    }

    private val _event = Channel<LogEvent>(Channel.BUFFERED)
    val event: Flow<LogEvent> = _event.receiveAsFlow()

    var loadingState by mutableStateOf(true)
        private set
    private var hasLoadedOnce = false

    // --- su log：直接从 Room 响应式派生，新增/清空自动刷新（不置 loadingState，避免整屏 spinner 闪烁）
    val suLogs: StateFlow<List<SuLog>> = repo.observeSuLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    // --- magisk log：shell 数据源，仍按需加载
    val magiskLogEntriesState = mutableStateListOf<MagiskLogEntry>()
    var magiskLogRaw = " "

    fun ensureLoaded() {
        if (hasLoadedOnce) return
        startLoading()
    }

    override suspend fun doLoadWork() {
        loadingState = true

        try {
            val raw = withContext(Dispatchers.Default) { repo.fetchMagiskLogs() }
            magiskLogRaw = raw
            val entries = MagiskLogParser.parse(raw).asReversed()

            magiskLogEntriesState.clear()
            magiskLogEntriesState.addAll(entries)
            hasLoadedOnce = true
        } catch (e: Throwable) {
            _event.trySend(LogEvent.ShowSnackbar(R.string.failure.asText()))
        } finally {
            loadingState = false
        }
    }

    fun saveMagiskLog() = withExternalRW {
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "magisk_log_%s.log".format(
                System.currentTimeMillis().toTime(timeFormatStandard))
            val logFile = MediaStoreUtils.getFile(filename)
            try {
                logFile.uri.outputStream().bufferedWriter().use { file ->
                    file.write("---Detected Device Info---\n\n")
                    file.write("isAB=${Info.isAB}\n")
                    file.write("isSAR=${Info.isSAR}\n")
                    file.write("ramdisk=${Info.ramdisk}\n")
                    val uname = Os.uname()
                    file.write("kernel=${uname.sysname} ${uname.machine} ${uname.release} ${uname.version}\n")

                    file.write("\n\n---System Properties---\n\n")
                    Shell.cmd("getprop").await().out.forEach { file.write("$it\n") }

                    file.write("\n\n---Environment Variables---\n\n")
                    System.getenv().forEach { (key, value) -> file.write("${key}=${value}\n") }

                    file.write("\n\n---System MountInfo---\n\n")
                    FileInputStream("/proc/self/mountinfo").reader().use { it.copyTo(file) }

                    file.write("\n---Magisk Logs---\n")
                    file.write("${Info.env.versionString} (${Info.env.versionCode})\n\n")
                    if (Info.env.isActive) file.write(magiskLogRaw)

                    file.write("\n---Manager Logs---\n")
                    file.write("${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})\n\n")
                    val stdout = object : CallbackList<String>(Runnable::run) {
                        override fun onAddElement(s: String) {
                            file.write("$s\n")
                        }
                    }
                    Shell.cmd("logcat -d").to(stdout).await()
                }
                _event.trySend(LogEvent.ShowSnackbar(logFile.toString().asText()))
            } catch (e: Exception) {
                _event.trySend(LogEvent.ShowSnackbar(R.string.failure.asText()))
            }
        }
    }

    fun clearMagiskLog() = repo.clearMagiskLogs {
        _event.trySend(LogEvent.ShowSnackbar(R.string.logs_cleared.asText()))
        startLoading()
    }

    fun clearLog() = viewModelScope.launch {
        repo.clearLogs()
        _event.trySend(LogEvent.ShowSnackbar(R.string.logs_cleared.asText()))
        startLoading()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
