package com.galaxywk.originalmedialibrary.android

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

data class ResolvedAsset(val url: String, val kind: String, val sequence: Int)
data class ResolvedWork(val sourceUrl: String, val awemeId: String?, val title: String, val author: String?, val assets: List<ResolvedAsset>)

object LinkParser {
    private val urlPattern = Regex("https?://[^\\s，。；、！!?）)】]+")
    private val idPattern = Regex("/(?:note|video|share/video)/(\\d+)")
    fun extract(text: String): List<String> = urlPattern.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') }.distinct().toList()
    fun id(url: String): String? = runCatching {
        val host = URI(url).host.orEmpty().lowercase()
        if (host != "douyin.com" && !host.endsWith(".douyin.com") && host != "iesdouyin.com" && !host.endsWith(".iesdouyin.com")) null
        else idPattern.find(URI(url).path)?.groupValues?.getOrNull(1)
    }.getOrNull()
}

class DouyinSession(context: Context) {
    private val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
    val webView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/150.0.0.0 Mobile Safari/537.36"
    }
    fun openLogin() { webView.loadUrl("https://www.douyin.com/") }
    fun loggedIn(): Boolean = cookieManager.getCookie("https://www.douyin.com/")?.isNotBlank() == true
    fun cookies(): String = cookieManager.getCookie("https://www.douyin.com/").orEmpty()
}

class DouyinResolver(private val session: DouyinSession) {
    private val client = OkHttpClient.Builder().followRedirects(true).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun resolve(text: String): List<ResolvedWork> = withContext(Dispatchers.IO) {
        if (!session.loggedIn()) error("请先在设置中完成抖音登录")
        LinkParser.extract(text).map { resolveOne(it) }
    }

    private fun resolveOne(source: String): ResolvedWork {
        val request = Request.Builder().url(source).header("User-Agent", session.webView.settings.userAgentString).header("Cookie", session.cookies()).build()
        val response = client.newCall(request).execute()
        val canonical = response.request.url.toString()
        val id = LinkParser.id(canonical) ?: error("无法识别抖音作品链接")
        val apiUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=$id"
        val detailRequest = Request.Builder().url(apiUrl).header("User-Agent", session.webView.settings.userAgentString).header("Cookie", session.cookies()).build()
        val root = client.newCall(detailRequest).execute().use { JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject }
        val detail = root.getAsJsonObject("aweme_detail") ?: error("抖音没有返回作品详情")
        val title = detail.get("desc")?.asString?.ifBlank { id } ?: id
        val author = detail.getAsJsonObject("author")?.get("nickname")?.asString
        val assets = mutableListOf<ResolvedAsset>()
        detail.getAsJsonObject("video")?.getAsJsonObject("play_addr")?.getAsJsonArray("url_list")?.firstOrNull()?.asString?.let { assets += ResolvedAsset(it, "video", 1) }
        detail.getAsJsonArray("images")?.forEachIndexed { index, image ->
            image.asJsonObject.getAsJsonObject("display_image")?.getAsJsonArray("url_list")?.firstOrNull()?.asString?.let { assets += ResolvedAsset(it, "image", index + 1) }
        }
        if (assets.isEmpty()) error("页面详情中没有找到原始媒体")
        return ResolvedWork(canonical, id, title, author, assets)
    }
}
