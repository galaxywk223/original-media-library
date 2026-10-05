package com.galaxywk.originalmedialibrary.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ResolvedAsset(val url: String, val kind: String, val sequence: Int)
data class ResolvedWork(val sourceUrl: String, val awemeId: String?, val title: String, val author: String?, val assets: List<ResolvedAsset>)
data class ParsedSource(val url: String, val awemeId: String?, val duplicate: Boolean = false)

object LinkParser {
    private val urlPattern = Regex("https?://[^\\s，。；、！!?）)】]+")
    private val idPattern = Regex("/(?:note|video|share/video)/(\\d+)")
    fun supported(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        uri.scheme == "https" && listOf("douyin.com", "iesdouyin.com").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
    fun extract(text: String): List<String> = urlPattern.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ':') }.filter { supported(it) }.distinct().toList()
    fun id(url: String): String? = if (supported(url)) idPattern.find(URI(url).path)?.groupValues?.getOrNull(1) else null
}

object WorkDetailParser {
    private fun find(value: JsonElement?, expected: String?, depth: Int = 0): JsonObject? {
        if (value == null || depth > 24) return null
        if (value.isJsonObject) {
            val obj = value.asJsonObject
            val id = obj.get("aweme_id")?.takeIf { it.isJsonPrimitive }?.asString
            if (id != null && (expected == null || expected == id) && (obj.has("video") || obj.has("images"))) return obj
            obj.entrySet().forEach { find(it.value, expected, depth + 1)?.let { result -> return result } }
        } else if (value.isJsonArray) value.asJsonArray.forEach { find(it, expected, depth + 1)?.let { result -> return result } }
        return null
    }
    private fun urls(obj: JsonObject?): String? = listOf("url_list", "download_url_list").firstNotNullOfOrNull { key ->
        obj?.getAsJsonArray(key)?.firstOrNull()?.asString?.takeIf { it.startsWith("https://") }
    }
    fun parse(json: String, source: String): ResolvedWork {
        require(json.length <= 4_000_000) { "作品详情过大" }
        val detail = find(JsonParser.parseString(json), LinkParser.id(source)) ?: error("页面尚未返回当前作品详情")
        val images = detail.getAsJsonArray("images")
        val assets = if (images != null && images.size() > 0) images.mapIndexedNotNull { index, item ->
            val image = item.asJsonObject
            val url = urls(image.getAsJsonObject("display_image")) ?: urls(image)
                ?: image.getAsJsonArray("download_url_list")?.firstOrNull()?.asString
            url?.let { ResolvedAsset(it, "image", index + 1) }
        } else {
            val video = detail.getAsJsonObject("video")
            val url = urls(video?.getAsJsonObject("play_addr")) ?: urls(video?.getAsJsonObject("download_addr"))
            listOfNotNull(url?.let { ResolvedAsset(it, "video", 1) })
        }
        require(assets.isNotEmpty()) { "页面没有返回可下载媒体" }
        val id = detail.get("aweme_id")?.asString
        return ResolvedWork(source, id, detail.get("desc")?.asString?.ifBlank { id } ?: id ?: "未命名作品",
            detail.getAsJsonObject("author")?.get("nickname")?.asString, assets)
    }
}

class DouyinSession(private val context: Context) {
    companion object {
        const val HOME = "https://www.douyin.com/"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
    val loginView: WebView by lazy { createWebView() }
    val webView get() = loginView
    fun createWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "浏览器必须在主线程创建" }
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !LinkParser.supported(request.url.toString())
            }
        }
    }
    fun openLogin() { loginView.loadUrl(HOME) }
    fun flush() { CookieManager.getInstance().flush() }
    fun loggedIn(): Boolean = cookies().split(';').any {
        it.trim().substringBefore('=') in listOf("sessionid", "sessionid_ss", "sid_tt") && it.substringAfter('=', "").isNotBlank()
    }
    fun cookies(url: String = HOME): String = CookieManager.getInstance().getCookie(url).orEmpty()
}

class DouyinResolver(private val session: DouyinSession) {
    suspend fun resolve(source: String): ResolvedWork = withContext(Dispatchers.Main) {
        require(LinkParser.supported(source)) { "仅支持抖音链接" }
        withTimeout(60_000) {
            suspendCancellableCoroutine { continuation ->
                val view = session.createWebView()
                fun cleanup() { view.stopLoading(); view.destroy() }
                val complete: (String) -> Unit = { json ->
                    if (continuation.isActive) {
                        runCatching { WorkDetailParser.parse(json, source) }.onSuccess {
                            continuation.resume(it)
                            cleanup()
                        }
                    }
                }
                val hook = """
                    (() => {
                      if (window.__omlHook) return; window.__omlHook = true;
                      const emit = (url, text) => {
                        if (String(url).includes('/aweme/v1/web/aweme/detail/') && text.length < 4000000)
                          window.omlCapture.postMessage(text);
                      };
                      const originalFetch = window.fetch;
                      window.fetch = function(...args) {
                        return originalFetch.apply(this,args).then(response => {
                          response.clone().text().then(text => emit(response.url,text)).catch(()=>{});
                          return response;
                        });
                      };
                      const originalOpen = XMLHttpRequest.prototype.open;
                      XMLHttpRequest.prototype.open = function(method,url,...args) {
                        this.addEventListener('load',() => { try { emit(this.responseURL,this.responseText); } catch(e){} });
                        return originalOpen.call(this,method,url,...args);
                      };
                    })();
                """.trimIndent()
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                    !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    continuation.resumeWithException(IllegalStateException("请更新 Android System WebView 后重试"))
                    cleanup()
                    return@suspendCancellableCoroutine
                }
                val origins = setOf("https://*.douyin.com", "https://douyin.com", "https://*.iesdouyin.com", "https://iesdouyin.com")
                WebViewCompat.addWebMessageListener(view, "omlCapture", origins) { _, message, _, mainFrame, _ ->
                    if (mainFrame) message.data?.let(complete)
                }
                WebViewCompat.addDocumentStartJavaScript(view, hook, origins)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !LinkParser.supported(request.url.toString())
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!continuation.isActive) return
                        view.evaluateJavascript("""
                            (() => {
                              for (const id of ['RENDER_DATA','__NEXT_DATA__']) {
                                const el=document.getElementById(id);
                                if (el) { try { omlCapture.postMessage(decodeURIComponent(el.textContent)); } catch(e){} }
                              }
                              for(const key of ['_ROUTER_DATA','__INITIAL_STATE__']) {
                                if(window[key]) { try { omlCapture.postMessage(JSON.stringify(window[key])); } catch(e){} }
                              }
                            })();
                        """.trimIndent(), null)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("作品页加载失败，请检查网络或重新登录"))
                            cleanup()
                        }
                    }
                }
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { cleanup() } }
                view.loadUrl(source)
            }
        }
    }
}
