package io.github.seyud.weave.core.utils

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.seyud.weave.core.AppContext
import timber.log.Timber

enum class InstalledItemSource {
    ROOT,
    FALLBACK,
}

data class InstalledItemLoadResult<T>(
    val items: List<T>,
    val source: InstalledItemSource,
    val shouldRefreshFromRoot: Boolean,
)

object InstalledPackageLoader {

    fun loadApplications(
        flags: Int,
        packageManager: PackageManager = AppContext.packageManager,
    ): InstalledItemLoadResult<ApplicationInfo> {
        val packageResult = loadPackages(flags, packageManager)
        return InstalledItemLoadResult(
            items = dedupe(
                items = packageResult.items.mapNotNull { it.applicationInfo },
                keySelector = { it.packageName },
            ),
            source = packageResult.source,
            shouldRefreshFromRoot = packageResult.shouldRefreshFromRoot,
        )
    }

    // QUERY_ALL_PACKAGES is declared in the :shared manifest (merged into the
    // final app); a library-module lint run cannot see the merged result, and
    // the superuser list genuinely needs the full package inventory anyway.
    // The root path above is the primary source; this fallback covers
    // non-root / degraded modes where partial visibility is acceptable.
    @SuppressLint("QueryPermissionsNeeded")
    fun loadPackages(
        flags: Int,
        packageManager: PackageManager = AppContext.packageManager,
    ): InstalledItemLoadResult<PackageInfo> = loadInstalledItems(
        logLabel = "packages",
        rootLoad = { RootUtils.getPackages(flags) },
        fallbackLoad = { packageManager.getInstalledPackages(flags) },
        keySelector = { it.packageName },
    )

    private inline fun <T> loadInstalledItems(
        logLabel: String,
        rootLoad: () -> List<T>,
        fallbackLoad: () -> List<T>,
        keySelector: (T) -> String,
    ): InstalledItemLoadResult<T> {
        RootUtils.ensureServiceConnected()
        val rootItems = dedupe(rootLoad(), keySelector)
        if (rootItems.isNotEmpty()) {
            return InstalledItemLoadResult(
                items = rootItems,
                source = InstalledItemSource.ROOT,
                shouldRefreshFromRoot = false,
            )
        }

        val fallbackItems = runCatching(fallbackLoad)
            .onFailure { Timber.w(it, "PackageManager fallback failed for %s", logLabel) }
            .getOrDefault(emptyList())
            .let { dedupe(it, keySelector) }

        if (RootUtils.isServiceConnected()) {
            Timber.w("RootService returned no %s, using PackageManager fallback", logLabel)
        } else {
            Timber.d("RootService not ready, using PackageManager fallback for %s", logLabel)
        }

        return InstalledItemLoadResult(
            items = fallbackItems,
            source = InstalledItemSource.FALLBACK,
            shouldRefreshFromRoot = !RootUtils.isServiceConnected(),
        )
    }

    private inline fun <T> dedupe(
        items: List<T>,
        keySelector: (T) -> String,
    ): List<T> {
        val deduped = LinkedHashMap<String, T>(items.size)
        for (item in items) {
            deduped.putIfAbsent(keySelector(item), item)
        }
        return deduped.values.toList()
    }
}
