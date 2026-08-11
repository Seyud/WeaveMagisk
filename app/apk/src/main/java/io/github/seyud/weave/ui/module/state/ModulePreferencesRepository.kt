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
 * 模块页偏好设置的数据仓库
 * 封装 SharedPreferences 读写，使 ViewModel 不直接依赖 Android 框架
 */
class ModulePreferencesRepository(
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val KEY_SORT_ENABLED = "module_sort_enabled_first"
        private const val KEY_SORT_UPDATE = "module_sort_update_first"
        private const val KEY_SORT_EXECUTABLE = "module_sort_executable_first"
        private const val KEY_HIDDEN_MODULE_IDS = "module_hidden_ids"
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

    /**
     * 加载被隐藏的模块 ID 集合
     * 防御性复制：SharedPreferences 返回的集合不保证不可变
     */
    fun loadHiddenModuleIds(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_MODULE_IDS, emptySet())?.toSet() ?: emptySet()

    fun saveHiddenModuleIds(ids: Set<String>) {
        prefs.edit {
            putStringSet(KEY_HIDDEN_MODULE_IDS, ids)
        }
    }
}
