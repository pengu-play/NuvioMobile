package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.httpGetTextLines
import com.nuvio.app.features.plugins.PluginRepositoryItem
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.streams_plugin_repository_fallback
import org.jetbrains.compose.resources.getString

internal data class InstalledStreamAddonTarget(
    val addonName: String,
    val addonId: String,
    val manifest: AddonManifest,
)

internal fun ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"

internal data class PluginProviderGroup(
    val addonId: String,
    val addonName: String,
    val scrapers: List<PluginScraper>,
)

internal sealed interface StreamLoadCompletion {
    data class Addon(val group: AddonStreamGroup) : StreamLoadCompletion
    data class PluginScraper(
        val addonId: String,
        val streams: List<StreamItem>,
        val error: String?,
    ) : StreamLoadCompletion
}

internal fun List<PluginScraper>.toPluginProviderGroups(
    repositories: List<PluginRepositoryItem>,
    groupByRepository: Boolean,
): List<PluginProviderGroup> {
    if (!groupByRepository) {
        return map { scraper ->
            PluginProviderGroup(
                addonId = "plugin:${scraper.id}",
                addonName = scraper.name,
                scrapers = listOf(scraper),
            )
        }
    }

    val repoNameByUrl = repositories.associate { it.manifestUrl to it.name }
    return groupBy { it.repositoryUrl }
        .map { (repositoryUrl, scrapers) ->
            PluginProviderGroup(
                addonId = "plugin-repo:${repositoryUrl.lowercase()}",
                addonName = repoNameByUrl[repositoryUrl].orEmpty().ifBlank { repositoryUrl.fallbackRepositoryLabel() },
                scrapers = scrapers.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.addonName.lowercase() }
}

internal fun List<AddonStreamGroup>.toEmptyStateReason(anyLoading: Boolean): StreamsEmptyStateReason? {
    if (anyLoading || any { it.streams.isNotEmpty() }) {
        return null
    }

    return if (isNotEmpty() && all { !it.error.isNullOrBlank() }) {
        StreamsEmptyStateReason.StreamFetchFailed
    } else {
        StreamsEmptyStateReason.NoStreamsFound
    }
}

internal suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal fun PluginRuntimeResult.toStreamItem(
    scraper: PluginScraper,
    addonName: String = scraper.name,
    addonId: String = "plugin:${scraper.id}",
    includeScraperNameInSubtitle: Boolean = false,
): StreamItem {
    val subtitleParts = listOfNotNull(
        scraper.name.takeIf { includeScraperNameInSubtitle && it.isNotBlank() },
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    val requestHeaders = headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val headerName = key.trim()
            val headerValue = value.trim()
            if (headerName.isBlank() || headerValue.isBlank() || headerName.equals("Range", ignoreCase = true)) {
                null
            } else {
                headerName to headerValue
            }
        }
        .toMap()

    return StreamItem(
        name = name ?: title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        sourceName = scraper.name,
        addonName = addonName,
        addonId = addonId,
        streamType = normalizeStreamType(type),
        behaviorHints = if (requestHeaders.isEmpty()) {
            StreamBehaviorHints()
        } else {
            StreamBehaviorHints(
                notWebReady = true,
                proxyHeaders = StreamProxyHeaders(request = requestHeaders),
            )
        },
        externalSubtitles = subtitles?.map {
            StreamSubtitle(
                url = it.url,
                language = it.language,
                name = it.name,
                headers = it.headers
            )
        } ?: emptyList()
    )
}

internal fun List<StreamItem>.sortedForGroupedDisplay(): List<StreamItem> =
    sortedWith(
        compareBy<StreamItem>(
            { it.sourceName.orEmpty().lowercase() },
            { it.streamLabel.lowercase() },
            { it.streamSubtitle.orEmpty().lowercase() },
        ),
    )

private fun String.fallbackRepositoryLabel(): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank {
            runBlocking { getString(Res.string.streams_plugin_repository_fallback) }
        }
    }
}

internal const val NdjsonContentType = "application/x-ndjson"

internal fun isNdjsonContentType(contentType: String?): Boolean =
    contentType
        ?.substringBefore(';')
        ?.trim()
        ?.equals(NdjsonContentType, ignoreCase = true) == true

internal suspend fun fetchAddonStreamsIncrementally(
    url: String,
    forceRefresh: Boolean,
    addonName: String,
    addonId: String,
    addonLogo: String?,
    onIntermediateGroup: suspend (AddonStreamGroup) -> Unit,
): AddonStreamGroup {
    val headers = buildMap {
        put("Accept", "application/json, $NdjsonContentType")
        if (forceRefresh) put("Cache-Control", "no-cache")
    }

    var isNdjson = false
    var sawNdjsonBatch = false
    val rawChunks = StringBuilder()
    val streamsByKey = linkedMapOf<String, StreamItem>()

    httpGetTextLines(
        url = url,
        headers = headers,
        onContentType = { contentType -> isNdjson = isNdjsonContentType(contentType) },
        onLine = { line ->
            if (rawChunks.isNotEmpty()) rawChunks.append('\n')
            rawChunks.append(line)
            if (isNdjson && line.isNotBlank()) {
                val batch = StreamParser.parseNdjsonBatch(line, addonName, addonId, addonLogo)
                if (batch.isNotEmpty()) {
                    sawNdjsonBatch = true
                    val batchByKey = LinkedHashMap<String, StreamItem>(batch.size)
                    batch.forEach { stream -> batchByKey[stream.dedupKey()] = stream }
                    // A cumulative snapshot line (one already carrying every
                    // stream seen so far) is authoritative for ordering, so its
                    // batches replace accumulated state; deltas keep appending.
                    if (batchByKey.keys.containsAll(streamsByKey.keys)) {
                        streamsByKey.clear()
                    }
                    streamsByKey.putAll(batchByKey)
                    onIntermediateGroup(
                        AddonStreamGroup(
                            addonName = addonName,
                            addonId = addonId,
                            streams = streamsByKey.values.toList(),
                            isLoading = true,
                        )
                    )
                }
            }
        },
    )

    val rawPayload = rawChunks.toString()
    return when {
        sawNdjsonBatch -> AddonStreamGroup(
            addonName = addonName,
            addonId = addonId,
            streams = streamsByKey.values.toList(),
            isLoading = false,
        )

        !isNdjson -> {
            if (rawPayload.isBlank()) {
                throw IllegalStateException(getString(Res.string.network_empty_response_body))
            }
            AddonStreamGroup(
                addonName = addonName,
                addonId = addonId,
                streams = StreamParser.parse(rawPayload, addonName, addonId, addonLogo),
                isLoading = false,
            )
        }

        rawPayload.isBlank() -> AddonStreamGroup(
            addonName = addonName,
            addonId = addonId,
            streams = emptyList(),
            isLoading = false,
        )

        // Declared NDJSON but no batch decoded: some addons label a single
        // regular JSON document as NDJSON. Recover it before failing.
        else -> {
            val recovered = try {
                StreamParser.parse(rawPayload, addonName, addonId, addonLogo)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                emptyList()
            }
            AddonStreamGroup(addonName, addonId, recovered, isLoading = false)
        }
    }
}

private fun StreamItem.dedupKey(): String =
    infoHash?.let { hash -> "$hash:${fileIdx ?: ""}" }
        ?: clientResolve?.infoHash?.let { hash -> "$hash:${clientResolve.fileIdx}" }
        ?: url?.trim()?.takeIf { it.isNotEmpty() }
        ?: externalUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(name, title).joinToString(":")
