package io.github.seyud.weave.ui.module.state

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 排序选项数据类
 */
data class SortOptions(
    val enabledFirst: Boolean = false,
    val updateFirst: Boolean = false,
    val executableFirst: Boolean = false,
)

/**
 * 模块页排序偏好的数据仓库
 * 封装 SharedPreferences 读写，使 ViewModel 不直接依赖 Android 框架
 */
class ModulePreferencesRepository(
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val KEY_SORT_ENABLED = "module_sort_enabled_first"
        private const val KEY_SORT_UPDATE = "module_sort_update_first"
        private const val KEY_SORT_EXECUTABLE = "module_sort_executable_first"
    }

    fun loadSortOptions(): SortOptions = SortOptions(
        enabledFirst = prefs.getBoolean(KEY_SORT_ENABLED, false),
        updateFirst = prefs.getBoolean(KEY_SORT_UPDATE, false),
        executableFirst = prefs.getBoolean(KEY_SORT_EXECUTABLE, false),
    )

    fun saveSortOptions(options: SortOptions) {
        prefs.edit {
            putBoolean(KEY_SORT_ENABLED, options.enabledFirst)
            putBoolean(KEY_SORT_UPDATE, options.updateFirst)
            putBoolean(KEY_SORT_EXECUTABLE, options.executableFirst)
        }
    }
}
