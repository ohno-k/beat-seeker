package com.beatseeker.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 【クラスの役割】 非表示の WebView で eagate を開き、収集スクリプトを注入して
 * スコア CSV と ARENA データを取得する。
 *
 * これがアプリ版の中核。ブラウザのクロスオリジン制約により beat-seeker のページからは
 * eagate をユーザーの Cookie 付きで取得できないが、アプリなら「eagate を開いた WebView の中で
 * 自前のスクリプトを走らせる」ことができるため、ユーザー操作 1 回で取得まで完了できる。
 *
 * 処理の流れ:
 *  手順1: 非表示 WebView に eagate の djdata ページを読み込む（Cookie は CookieManager が永続化）。
 *  手順2: 読み込み後の URL がログインページなら未ログインと判断し、`onNeedLogin` を返す。
 *  手順3: beat-seeker から収集スクリプト（/native-scraper.js）をダウンロードし、
 *         `evaluateJavascript` で注入する。`<script>` タグではなく直接評価するのは、
 *         eagate 側の CSP に左右されないようにするため。
 *  手順4: スクリプトは `window.bsBridge`（WebMessageListener）へ進捗と結果を送ってくる。
 *         結果 JSON は分割して送られるので、ここで seq 順に組み立てる。
 *
 * セキュリティ:
 *  - eagate は外部サイトなので `addJavascriptInterface`（ページ全体に無条件でネイティブ API を
 *    露出する）は使わない。オリジンを限定できる {@link WebViewCompat.addWebMessageListener} を使い、
 *    許可オリジンを eagate だけに絞る。
 *  - この WebView は eagate 以外へのナビゲーションを拒否する。
 */
class EagateScraper(
    private val context: Context,
    private val container: ViewGroup,
    private val callbacks: Callbacks,
) {

    /** 【インターフェース】 収集の進行と結果を受け取る側（= WebView A へ橋渡しする MainActivity）。 */
    interface Callbacks {
        /** 進捗メッセージ。 */
        fun onProgress(message: String)
        /** 結果 JSON（ブックマークレットと同一形式）。 */
        fun onResult(json: String)
        /** eagate 未ログイン。呼び出し側はログイン画面を出す。 */
        fun onNeedLogin()
        /** 収集失敗。 */
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var webView: WebView? = null
    /** 受信済みチャンク。index = seq。 */
    private var chunks: Array<String>? = null
    private var received = 0
    /** 二重完了（タイムアウトとの競合など）を防ぐフラグ。 */
    private var finished = false
    /** 収集スクリプトを注入済みか。リダイレクト等で onPageFinished が複数回来ても 1 回だけ注入する。 */
    private var injected = false

    /** 収集全体のタイムアウト。全レベル巡回は端末と回線によっては数分かかる。 */
    private val timeoutRunnable = Runnable { fail("timeout") }

    /** 収集中かどうか。UI 側の二重起動防止に使う。 */
    var isRunning: Boolean = false
        private set

    /**
     * 【関数の役割】 収集を開始する。
     *
     * @param eagateUrl 最初に開く eagate のページ。作品バージョンを含む URL を Web 側から受け取る
     *                  （新作稼働時にアプリを再リリースせずに済ませるため）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(eagateUrl: String) {
        if (isRunning) return
        if (!Eagate.isEagateUrl(eagateUrl)) {
            callbacks.onError("invalid eagate url")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // 端末の WebView が古い場合。Play ストアからの WebView 更新を促す。
            callbacks.onError("webview too old")
            return
        }

        isRunning = true
        finished = false
        injected = false
        chunks = null
        received = 0

        val wv = WebView(context)
        webView = wv
        container.addView(wv, ViewGroup.LayoutParams(1, 1))

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // eagate はスマホ向け表示でも同じ djdata を返すため UA はデフォルトのままでよい。
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        // 収集スクリプトからのメッセージ受け口。許可オリジンを eagate に限定する。
        WebViewCompat.addWebMessageListener(
            wv,
            "bsBridge",
            Eagate.ALLOWED_ORIGIN_RULES,
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            if (sourceOrigin?.host != Eagate.HOST) return@addWebMessageListener
            handleMessage(message.data ?: return@addWebMessageListener)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // この WebView は eagate 専用。外部へのナビゲーションは行わせない。
                return !Eagate.isEagateUrl(request?.url?.toString())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (finished) return
                if (Eagate.isLoginUrl(url)) {
                    needLogin()
                    return
                }
                injectScraper()
            }
        }

        callbacks.onProgress("IIDX公式サイトに接続中…")
        main.postDelayed(timeoutRunnable, TIMEOUT_MS)
        wv.loadUrl(eagateUrl)
    }

    /**
     * 【関数の役割】 収集スクリプトを beat-seeker からダウンロードし、WebView 内で評価する。
     * `<script src>` ではなく `evaluateJavascript` で直接流し込むため、eagate 側の CSP の影響を受けない。
     */
    private fun injectScraper() {
        if (injected) return
        injected = true
        callbacks.onProgress("取得スクリプトを準備中…")
        val submitted = try {
            io.execute(buildInjectTask())
            true
        } catch (e: Exception) {
            // destroy() 後などにエグゼキュータが停止していた場合。
            false
        }
        if (!submitted) fail("scraper unavailable")
    }

    /** 【関数の役割】 スクリプトのダウンロードと評価を行うワーカー処理を組み立てる。 */
    private fun buildInjectTask(): Runnable = Runnable {
        val source = try {
            downloadText(BuildConfig.SCRAPER_SCRIPT_URL)
        } catch (e: Exception) {
            main.post { fail("script download failed: ${e.message}") }
            return@Runnable
        }
        main.post {
            if (finished) return@post
            webView?.evaluateJavascript(source, null)
        }
    }

    /** 【関数の役割】 収集スクリプトからの 1 メッセージを処理する。 */
    private fun handleMessage(raw: String) {
        if (finished) return
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        when (msg.optString("kind")) {
            "progress" -> callbacks.onProgress(msg.optString("message"))
            "needLogin" -> needLogin()
            "error" -> fail(msg.optString("message", "scrape error"))
            "chunk" -> {
                val total = msg.optInt("total", 0)
                val seq = msg.optInt("seq", -1)
                if (total <= 0 || seq < 0 || seq >= total) return
                val buf = chunks?.takeIf { it.size == total } ?: Array(total) { "" }.also {
                    chunks = it
                    received = 0
                }
                // 同じ seq が二重に届いても受信数を二重計上しない。
                if (buf[seq].isEmpty()) received++
                buf[seq] = msg.optString("data")
                callbacks.onProgress("データ受信中… $received/$total")
                if (received >= total) succeed(buf.joinToString(""))
            }
        }
    }

    /** 【関数の役割】 収集成功。結果を返して後始末する。 */
    private fun succeed(json: String) {
        if (finished) return
        finished = true
        cleanup()
        callbacks.onResult(json)
    }

    /** 【関数の役割】 未ログイン。呼び出し側にログイン画面を出させる。 */
    private fun needLogin() {
        if (finished) return
        finished = true
        cleanup()
        callbacks.onNeedLogin()
    }

    /** 【関数の役割】 収集失敗。 */
    private fun fail(message: String) {
        if (finished) return
        finished = true
        cleanup()
        callbacks.onError(message)
    }

    /** 【関数の役割】 非表示 WebView とタイマーを破棄する。 */
    private fun cleanup() {
        main.removeCallbacks(timeoutRunnable)
        isRunning = false
        chunks = null
        received = 0
        webView?.let {
            it.stopLoading()
            container.removeView(it)
            it.destroy()
        }
        webView = null
        CookieManager.getInstance().flush()
    }

    /** 【関数の役割】 呼び出し側の Activity 破棄に合わせて解放する。 */
    fun destroy() {
        finished = true
        cleanup()
        io.shutdownNow()
    }

    /** 【関数の役割】 指定 URL のテキストを取得する（収集スクリプトのダウンロード用）。 */
    private fun downloadText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        /** 全レベル巡回の想定所要時間に余裕を持たせた上限。 */
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
