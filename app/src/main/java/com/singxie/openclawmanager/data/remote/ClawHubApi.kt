package com.singxie.openclawmanager.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Top ClawHub Skills 公开 API，用于搜索与获取热门 Skill 列表。
 * 见 https://playbooks.com/skills/openclaw/skills/topclawhubskills
 */
private const val BASE_URL = "https://topclawhubskills.com/api"

data class ClawHubSkillItem(
    val slug: String,
    @SerializedName("display_name") val displayName: String,
    val summary: String?,
    val downloads: Long = 0,
    val stars: Int = 0,
    @SerializedName("owner_handle") val ownerHandle: String? = null,
    @SerializedName("is_certified") val isCertified: Boolean = false,
    @SerializedName("clawhub_url") val clawhubUrl: String? = null
)

data class ClawHubSearchResponse(
    val ok: Boolean = false,
    val data: List<ClawHubSkillItem>? = null,
    val total: Int = 0,
    val limit: Int = 0
)

private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader("User-Agent", "OpenClawManager/1.0 (Android)")
                .addHeader("Accept", "application/json")
                .build()
        )
    }
    .build()

private val gson = Gson()

private fun get(url: String): Result<String> = runCatching {
    val request = Request.Builder().url(url).get().build()
    val response = client.newCall(request).execute()
    val body = response.body?.string()
    if (!response.isSuccessful) return@runCatching throw Exception("HTTP ${response.code}: ${body?.take(200)}")
    body ?: throw Exception("空响应")
}

fun searchClawHubSkills(query: String, limit: Int = 20): Result<List<ClawHubSkillItem>> {
    if (query.isBlank()) return Result.success(emptyList())
    val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
    val url = "$BASE_URL/search?q=$encoded&limit=$limit"
    return get(url).mapCatching { body ->
        val parsed = gson.fromJson(body, ClawHubSearchResponse::class.java)
        parsed.data ?: emptyList()
    }
}

fun fetchTopClawHubDownloads(limit: Int = 15): Result<List<ClawHubSkillItem>> {
    val url = "$BASE_URL/top-downloads?limit=$limit"
    return get(url).mapCatching { body ->
        val parsed = gson.fromJson(body, ClawHubSearchResponse::class.java)
        parsed.data ?: emptyList()
    }
}
