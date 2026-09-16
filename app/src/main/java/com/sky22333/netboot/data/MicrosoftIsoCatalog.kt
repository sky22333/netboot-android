package com.sky22333.netboot.data

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class WindowsVersion(val product: String) {
    Windows11("Windows 11"),
    Windows10("Windows 10"),
}

enum class IsoLanguage(val locale: String, val acceptLanguage: String) {
    Chinese("zh-CN", "zh-CN,zh;q=0.9"),
    English("en-US", "en-US,en;q=0.9"),
}

enum class IsoArchitecture(val apiType: Int) {
    X64(1),
    Arm64(2),
}

data class IsoRequest(
    val version: WindowsVersion,
    val language: IsoLanguage,
    val architecture: IsoArchitecture,
)

data class TemporaryIsoLink(
    val url: String,
    val expiresAt: Long,
)

@Singleton
class MicrosoftIsoCatalog @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(request: IsoRequest): TemporaryIsoLink = withContext(Dispatchers.IO) {
        val edition = editionId(request)
        val session = UUID.randomUUID().toString()
        prepareSession(session, request.language)
        val skuUrl = "https://www.microsoft.com/software-download-connector/api/getskuinformationbyproductedition" +
            "?profile=$ProfileId&productEditionId=$edition&SKU=undefined&friendlyFileName=undefined" +
            "&Locale=${request.language.locale}&sessionID=${encode(session)}"
        val skuResponse = json.decodeFromString<SkuResponse>(get(skuUrl, request))
        apiError(skuResponse.errors)?.let { throw CatalogException("microsoft_api", it) }
        val sku = skuResponse.skus.firstOrNull { item ->
            val label = "${item.language} ${item.localizedLanguage}".lowercase()
            when (request.language) {
                IsoLanguage.Chinese -> label.contains("chinese") && label.contains("simplified")
                IsoLanguage.English -> label.contains("english")
            }
        }?.id?.asId() ?: throw CatalogException("language_unavailable")
        val linkUrl = "https://www.microsoft.com/software-download-connector/api/GetProductDownloadLinksBySku" +
            "?profile=$ProfileId&productEditionId=undefined&SKU=${encode(sku)}&friendlyFileName=undefined" +
            "&Locale=${request.language.locale}&sessionID=${encode(session)}"
        val linkResponse = json.decodeFromString<LinkResponse>(get(linkUrl, request))
        apiError(linkResponse.errors)?.let { throw CatalogException("microsoft_api", it) }
        val url = linkResponse.options.firstOrNull {
            it.downloadType == request.architecture.apiType && it.uri.isNotBlank()
        }?.uri ?: throw CatalogException("architecture_unavailable")
        MicrosoftHosts.requireOfficial(url)
        TemporaryIsoLink(url, parseExpiry(url) ?: System.currentTimeMillis() + DefaultLifetimeMillis)
    }

    private fun prepareSession(session: String, language: IsoLanguage) {
        getRaw("https://vlscppe.microsoft.com/tags?org_id=$OrgId&session_id=${encode(session)}", language, "")
        val script = getRaw(
            "https://ov-df.microsoft.com/mdt.js?instanceId=$InstanceId&PageId=si&session_id=${encode(session)}",
            language,
            "",
        )
        val w = extract(script, "w")
        val rticks = extract(script, "rticks")
        val verifyUrl = "https://ov-df.microsoft.com/?session_id=${encode(session)}&CustomerId=$InstanceId" +
            "&PageId=si&w=${encode(w)}&mdt=${System.currentTimeMillis()}&rticks=${encode(rticks)}"
        getRaw(verifyUrl, language, "")
    }

    private fun get(url: String, request: IsoRequest): String =
        getRaw(url, request.language, referer(request))

    private fun getRaw(url: String, language: IsoLanguage, referer: String, redirects: Int = 0): String {
        if (redirects > MaxRedirects) throw CatalogException("too_many_redirects")
        MicrosoftHosts.requireOfficial(url)
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", language.acceptLanguage)
        if (referer.isNotEmpty()) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            if (response.code in 300..399) {
                val location = response.header("Location") ?: throw CatalogException("redirect_without_location")
                val target = response.request.url.resolve(location)?.toString() ?: throw CatalogException("invalid_redirect")
                MicrosoftHosts.requireOfficial(target)
                return getRaw(target, language, referer, redirects + 1)
            }
            if (!response.isSuccessful) throw CatalogException("http_${response.code}")
            return response.body.string()
        }
    }

    private fun editionId(request: IsoRequest): String = when (request.version to request.architecture) {
        WindowsVersion.Windows11 to IsoArchitecture.X64 -> "3321"
        WindowsVersion.Windows11 to IsoArchitecture.Arm64 -> "3324"
        WindowsVersion.Windows10 to IsoArchitecture.X64 -> "2618"
        WindowsVersion.Windows10 to IsoArchitecture.Arm64 -> throw CatalogException("windows_10_arm64_unavailable")
        else -> error("unreachable")
    }

    private fun referer(request: IsoRequest): String {
        val locale = if (request.language == IsoLanguage.Chinese) "zh-cn" else "en-us"
        val page = if (request.version == WindowsVersion.Windows11) "windows11" else "windows10ISO"
        return "https://www.microsoft.com/$locale/software-download/$page"
    }

    private fun extract(body: String, key: String): String {
        val pattern = Regex("${Regex.escape(key)}['\"]?\\s*[:=]\\s*['\"]?([A-Za-z0-9+/_-]+)")
        return pattern.find(body)?.groupValues?.get(1) ?: throw CatalogException("session_parse_failed")
    }

    private fun parseExpiry(url: String): Long? {
        val query = URI(url).rawQuery ?: return null
        val value = query.split('&').firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0].equals("e", ignoreCase = true)) pair[1].toLongOrNull() else null
        } ?: return null
        return if (value < 10_000_000_000L) value * 1000 else value
    }

    private fun JsonElement.asId(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun apiError(errors: List<ApiError>): String? = errors.firstOrNull()?.let { it.value ?: it.message }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val OrgId = "y6jn8c31"
        const val ProfileId = "606624d44113"
        const val InstanceId = "560dc9f3-1aa5-4a2f-b63c-9e18f8d0e175"
        const val UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/137 Safari/537.36"
        const val DefaultLifetimeMillis = 23L * 60 * 60 * 1000
        const val MaxRedirects = 5
    }
}

class CatalogException(val code: String, message: String? = null) : IOException(message ?: code)

object MicrosoftHosts {
    private val exactHosts = setOf("www.microsoft.com", "vlscppe.microsoft.com", "ov-df.microsoft.com")

    fun requireOfficial(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { throw CatalogException("invalid_url") }
        val host = uri.host?.lowercase() ?: throw CatalogException("invalid_url")
        if (uri.scheme != "https" || (host !in exactHosts && !host.endsWith(".microsoft.com"))) {
            throw CatalogException("untrusted_download_host")
        }
    }
}

@Serializable
private data class SkuResponse(
    @SerialName("Skus") val skus: List<Sku> = emptyList(),
    @SerialName("Errors") val errors: List<ApiError> = emptyList(),
)

@Serializable
private data class Sku(
    @SerialName("Id") val id: JsonElement,
    @SerialName("Language") val language: String = "",
    @SerialName("LocalizedLanguage") val localizedLanguage: String = "",
)

@Serializable
private data class LinkResponse(
    @SerialName("ProductDownloadOptions") val options: List<DownloadOption> = emptyList(),
    @SerialName("Errors") val errors: List<ApiError> = emptyList(),
)

@Serializable
private data class DownloadOption(
    @SerialName("DownloadType") val downloadType: Int,
    @SerialName("Uri") val uri: String,
)

@Serializable
private data class ApiError(
    @SerialName("Value") val value: String? = null,
    @SerialName("Message") val message: String? = null,
)
