package com.beatseeker.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.beatseeker.app.databinding.ActivityMainBinding
import org.json.JSONObject

/**
 * 【クラスの役割】 beat-seeker（PWA）を WebView で表示し、そこへ「1タップ取り込み」機能を注入する
 * アプリ本体の画面。
 *
 * アプリ版の存在理由はただ一つ、ブラウザのクロスオリジン制約の回避にある。
 * beat-seeker のページからは eagate をユーザーの Cookie 付きで取得できないため、Web 版では
 * ブックマークレット（登録が煩雑）が必要になる。アプリなら非表示 WebView で eagate を開けるので、
 * ユーザーは画面内のボタンを 1 回押すだけで取り込みまで完了する。
 *
 * 画面構成:
 *  - `webMain`         … beat-seeker 本体。ここにだけ `BeatSeekerNative` を注入する。
 *  - `hiddenContainer` … 収集用の非表示 WebView（{@link EagateScraper}）を置く器。
 *
 * セキュリティ:
 *  - `addJavascriptInterface` は「そのページ全体」にネイティブ API を露出させるため、
 *    `webMain` が beat-seeker 以外へ遷移しないよう {@link WebViewClient} で遷移先を制限し、
 *    外部リンクは端末のブラウザへ逃がす。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scraper: EagateScraper
    private val main = Handler(Looper.getMainLooper())

    /** ログイン画面から戻ったときに再実行するための、直前の eagate URL。 */
    private var pendingEagateUrl: String? = null

    /** eagate ログイン画面の起動ランチャ。ログイン成功なら取り込みを再開する。 */
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val url = pendingEagateUrl
        pendingEagateUrl = null
        if (result.resultCode == Activity.RESULT_OK && url != null) {
            scraper.start(url)
        } else {
            callPage("onError", "login cancelled")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CookieManager.getInstance().setAcceptCookie(true)

        binding.webMain.apply {
            settings.javaScriptEnabled = true
            // JWT を localStorage に保存しているため必須。
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (Eagate.isAppUrl(url)) return false
                    // 外部リンク（規約ページ、SNS 等）は端末のブラウザで開く。
                    // ネイティブ API を露出したままの WebView に外部サイトを読み込ませないため。
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // 対応アプリが無いスキーム（mailto 等）。開けないだけで WebView 内には読み込ませない。
                    }
                    return true
                }
            }

            // beat-seeker のページにだけネイティブ API を露出する。
            addJavascriptInterface(NativeApi(), "BeatSeekerNative")
            loadUrl(BuildConfig.APP_URL)
        }

        scraper = EagateScraper(this, binding.hiddenContainer, ScraperCallbacks())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webMain.canGoBack()) binding.webMain.goBack() else finish()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        // アプリが落ちても eagate / beat-seeker のログインを維持するため Cookie を書き出す。
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        scraper.destroy()
        super.onDestroy()
    }

    /**
     * 【クラスの役割】 beat-seeker のページから呼べるネイティブ API。
     * `window.BeatSeekerNative` として露出する（`useNativeBridge.ts` と対になる）。
     */
    private inner class NativeApi {
        /**
         * 【メソッドの役割】 取り込みを開始する。
         * @param eagateUrl 開く eagate のページ。作品バージョンを知っている Web 側から渡される。
         */
        @JavascriptInterface
        fun startImport(eagateUrl: String) {
            // JavascriptInterface は WebView の JS スレッドで呼ばれるため、UI 操作はメインスレッドへ移す。
            main.post {
                if (scraper.isRunning) return@post
                pendingEagateUrl = eagateUrl
                scraper.start(eagateUrl)
            }
        }

        /** 【メソッドの役割】 アプリのバージョン名を返す（Web 側の表示・不具合切り分け用）。 */
        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    /** 【クラスの役割】 収集結果を beat-seeker のページへ橋渡しする。 */
    private inner class ScraperCallbacks : EagateScraper.Callbacks {
        override fun onProgress(message: String) = callPage("onProgress", message)

        override fun onResult(json: String) {
            // 数百 KB になりうるため分割して渡す。1 回で渡すと WebView 側で落ちることがある。
            val total = maxOf(1, (json.length + CHUNK_SIZE - 1) / CHUNK_SIZE)
            for (seq in 0 until total) {
                val part = json.substring(
                    seq * CHUNK_SIZE,
                    minOf(json.length, (seq + 1) * CHUNK_SIZE),
                )
                evaluate(
                    "onResultChunk($seq, $total, ${JSONObject.quote(part)})"
                )
            }
        }

        override fun onNeedLogin() {
            // Cookie 切れ／未ログイン。ログイン画面を出し、成功したら自動で再実行する。
            loginLauncher.launch(Intent(this@MainActivity, EagateLoginActivity::class.java))
        }

        override fun onError(message: String) = callPage("onError", message)
    }

    /** 【関数の役割】 ページ側コールバックを文字列引数 1 つで呼ぶ。 */
    private fun callPage(method: String, arg: String) {
        evaluate("$method(${JSONObject.quote(arg)})")
    }

    /**
     * 【関数の役割】 `window.__beatSeekerNative` のメソッド呼び出しを WebView 上で評価する。
     * ページ側の準備前に呼ばれても落ちないよう、存在チェックを挟む。
     */
    private fun evaluate(call: String) {
        val js = "window.__beatSeekerNative && window.__beatSeekerNative.$call;"
        main.post { binding.webMain.evaluateJavascript(js, null) }
    }

    private companion object {
        /** ページへ 1 回で渡す最大文字数。 */
        const val CHUNK_SIZE = 64 * 1024
    }
}
