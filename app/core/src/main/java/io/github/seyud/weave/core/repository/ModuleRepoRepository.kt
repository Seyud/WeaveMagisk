package io.github.seyud.weave.core.repository

import android.net.Uri
import io.github.seyud.weave.core.Config
import io.github.seyud.weave.core.di.ServiceLocator
import io.github.seyud.weave.core.model.module.RepoAuthor
import io.github.seyud.weave.core.model.module.RepoModuleDetail
import io.github.seyud.weave.core.model.module.RepoModuleLatestRelease
import io.github.seyud.weave.core.model.module.RepoModuleSummary
import io.github.seyud.weave.core.model.module.RepoRelease
import io.github.seyud.weave.core.model.module.RepoReleaseAsset
import org.json.JSONArray
import org.json.JSONObject

interface ModuleRepoRepository {
    suspend fun fetchModules(baseUrl: String = Config.moduleRepoBaseUrl): Result<List<RepoModuleSummary>>

    suspend fun fetchModuleDetail(
        moduleId: String,
        baseUrl: String = Config.moduleRepoBaseUrl,
        forceRefresh: Boolean = false,
    ): Result<RepoModuleDetail>

    fun getCachedModuleDetail(
        moduleId: String,
        baseUrl: String = Config.moduleRepoBaseUrl,
    ): RepoModuleDetail?
}

class ModuleRepoRepositoryImpl(
    private val networkService: NetworkService = ServiceLocator.networkService,
) : ModuleRepoRepository {

    companion object {
        private val moduleDetailCache = linkedMapOf<String, RepoModuleDetail>()

        @Synchronized
        private fun readModuleDetailCache(key: String): RepoModuleDetail? = moduleDetailCache[key]

        @Synchronized
        private fun writeModuleDetailCache(
            key: String,
            value: RepoModuleDetail,
        ) {
            moduleDetailCache[key] = value
            while (moduleDetailCache.size > 48) {
                val oldestKey = moduleDetailCache.keys.firstOrNull() ?: break
                moduleDetailCache.remove(oldestKey)
            }
        }
    }

    override suspend fun fetchModules(baseUrl: String): Result<List<RepoModuleSummary>> = runCatching {
        val modulesUrl = buildModulesUrl(baseUrl)
        val response = networkService.fetchString(modulesUrl)
        val json = JSONArray(response)
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                parseModuleSummary(item)?.let(::add)
            }
        }
    }

    override suspend fun fetchModuleDetail(
        moduleId: String,
        baseUrl: String,
        forceRefresh: Boolean,
    ): Result<RepoModuleDetail> = runCatching {
        val cacheKey = buildDetailCacheKey(baseUrl, moduleId)
        if (!forceRefresh) {
            readModuleDetailCache(cacheKey)?.let { return@runCatching it }
        }
        val detailUrl = buildModuleDetailUrl(baseUrl, moduleId)
        val response = networkService.fetchString(detailUrl)
        val json = JSONObject(response)
        (parseModuleDetail(json) ?: error("Module detail not found")).also {
            writeModuleDetailCache(cacheKey, it)
        }
    }

    override fun getCachedModuleDetail(
        moduleId: String,
        baseUrl: String,
    ): RepoModuleDetail? {
        return Config.normalizeModuleRepoBaseUrl(baseUrl)?.let { normalizedBaseUrl ->
            readModuleDetailCache(buildDetailCacheKey(normalizedBaseUrl, moduleId))
        }
    }

    private fun buildModulesUrl(baseUrl: String): String {
        val normalizedBaseUrl = Config.normalizeModuleRepoBaseUrl(baseUrl)
            ?: error("Invalid module repository URL")
        return "$normalizedBaseUrl/modules.json"
    }

    private fun buildModuleDetailUrl(baseUrl: String, moduleId: String): String {
        val normalizedBaseUrl = Config.normalizeModuleRepoBaseUrl(baseUrl)
            ?: error("Invalid module repository URL")
        return "$normalizedBaseUrl/module/${Uri.encode(moduleId)}.json"
    }

    private fun buildDetailCacheKey(
        baseUrl: String,
        moduleId: String,
    ): String {
        val normalizedBaseUrl = Config.normalizeModuleRepoBaseUrl(baseUrl)
            ?: error("Invalid module repository URL")
        return "$normalizedBaseUrl|$moduleId"
    }

    private fun parseModuleSummary(json: JSONObject): RepoModuleSummary? {
        val moduleId = json.optString("moduleId").trim()
        if (moduleId.isEmpty()) {
            return null
        }

        return RepoModuleSummary(
            moduleId = moduleId,
            moduleName = json.optString("moduleName").trim(),
            authors = parseAuthors(json.optJSONArray("authors")),
            summary = json.optString("summary").trim(),
            metamodule = json.optBoolean("metamodule", false),
            stargazerCount = json.optInt("stargazerCount", 0),
            latestRelease = parseLatestRelease(json.optJSONObject("latestRelease")),
        )
    }

    private fun parseModuleDetail(json: JSONObject): RepoModuleDetail? {
        val moduleId = json.optString("moduleId").trim()
        if (moduleId.isEmpty()) {
            return null
        }

        return RepoModuleDetail(
            moduleId = moduleId,
            moduleName = json.optString("moduleName").trim(),
            url = json.optString("url").trim(),
            homepageUrl = json.optString("homepageUrl").trim(),
            sourceUrl = json.optString("sourceUrl").trim(),
            authors = parseAuthors(json.optJSONArray("authors")),
            summary = json.optString("summary").trim(),
            readme = json.optString("readme").trim(),
            readmeHtml = json.optString("readmeHTML").trim(),
            metamodule = json.optBoolean("metamodule", false),
            stargazerCount = json.optInt("stargazerCount", 0),
            releases = parseReleases(json.optJSONArray("releases")),
        )
    }

    private fun parseAuthors(array: JSONArray?): List<RepoAuthor> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) {
                    continue
                }
                add(
                    RepoAuthor(
                        name = name,
                        link = item.optString("link").trim(),
                    )
                )
            }
        }
    }

    private fun parseLatestRelease(json: JSONObject?): RepoModuleLatestRelease? {
        if (json == null) {
            return null
        }
        val name = json.optString("name").trim()
        val time = json.optString("time").trim()
        val version = json.optString("version").trim()
        val versionCode = json.optAnyInt("versionCode")
        val downloadUrl = json.optString("downloadUrl").trim().ifEmpty { null }
        if (name.isEmpty() && downloadUrl == null) {
            return null
        }
        return RepoModuleLatestRelease(
            name = name,
            time = time,
            version = version,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
        )
    }

    private fun parseReleases(array: JSONArray?): List<RepoRelease> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val tagName = item.optString("tagName").trim()
                val name = item.optString("name").trim().ifEmpty { tagName }
                add(
                    RepoRelease(
                        name = name,
                        tagName = tagName,
                        publishedAt = item.optString("publishedAt").trim(),
                        version = item.optString("version").trim(),
                        versionCode = item.optAnyInt("versionCode"),
                        isPrerelease = item.optBoolean("isPrerelease", false),
                        descriptionHtml = item.optString("descriptionHTML").trim(),
                        releaseAssets = parseReleaseAssets(item.optJSONArray("releaseAssets")),
                    )
                )
            }
        }.sortedByDescending { it.publishedAt }
    }

    private fun parseReleaseAssets(array: JSONArray?): List<RepoReleaseAsset> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val downloadUrl = item.optString("downloadUrl").trim()
                if (name.isEmpty() || downloadUrl.isEmpty()) {
                    continue
                }
                add(
                    RepoReleaseAsset(
                        name = name,
                        downloadUrl = downloadUrl,
                        size = item.optLong("size", 0L),
                        downloadCount = item.optAnyInt("downloadCount") ?: 0,
                    )
                )
            }
        }
    }
}

private fun JSONObject.optAnyInt(name: String): Int? {
    val value = opt(name)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        null -> null
        else -> null
    }
}
