package com.beatseeker.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.beatseeker.app.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File

/**
 * 【クラスの役割】 beat-seeker（PWA）を WebView で表示し、そこへ「1タップ取り込み」機能を注入する
 * アプリ本体の画面。
 *
 * アプリ版の存在理由はただ一つ、ブラウザのクロスオリジン制約の回避にある。
 * beat-seeker のページからは eagate をユーザーの Cookie 付きで取得できないため、Web 版では
 * ブックマークレット（登録が煩雑）が必要になる。アプリなら非表示 WebView で eagate を開けるので、
 * ユーザーは画面内のボタンを 1 回押すだけで取り込みまで完了する。
 *
 * もう一つ、WebView には Web Share API（navigator.share）が無いため、プレイ成果レポートの
 * 「画像付きで X にポスト」もここで肩代わりする。ページから PNG を受け取り、共有インテントで
 * X アプリの投稿画面を画像添付・本文入りの状態で直接開く（{@link NativeApi#shareImageEnd}）。
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

        // ── 共有画像（プレイ成果レポートの「画像付きで X にポスト」）──
        // WebView には navigator.share が無く、クリップボードに入れた画像は X アプリに貼れないため、
        // ページから PNG を受け取ってネイティブの共有インテントで X の投稿画面を開く。
        // 数 MB の Base64 を 1 回で渡すのは避け、begin / chunk / end の 3 段階で分割受信する
        // （JavascriptInterface は WebView の JS スレッドから順に呼ばれるので排他は不要）。

        /** 受信中の PNG（Base64 断片の連結）。 */
        private val shareImageBuf = StringBuilder()

        /** 【メソッドの役割】 共有画像の受信を始める（前回の残りを捨てる）。 */
        @JavascriptInterface
        fun shareImageBegin() {
            shareImageBuf.setLength(0)
        }

        /** 【メソッドの役割】 共有画像の Base64 断片を受け取る。 */
        @JavascriptInterface
        fun shareImageChunk(base64Part: String) {
            shareImageBuf.append(base64Part)
        }

        /**
         * 【メソッドの役割】 受信した PNG を共有する。X アプリが入っていれば投稿画面を画像添付・本文入りで
         * 直接開き、無ければ端末の共有シートを出す。
         * @param text 投稿本文（Web 側の共有テキストと同じ）。
         * @return "ok"。失敗時はその理由（Web 側はこれを見てブラウザ向けの経路へ切り替える）。
         */
        @JavascriptInterface
        fun shareImageEnd(text: String): String {
            val base64 = shareImageBuf.toString()
            shareImageBuf.setLength(0)
            if (base64.isEmpty()) return "empty image"
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                // 共有先が読み終わる前に消えないよう cache に置く（次回の共有で上書きする）。
                val dir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
                val file = File(dir, SHARE_FILE_NAME)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    file,
                )
                main.post { startImageShare(uri, text) }
                "ok"
            } catch (e: Exception) {
                e.message ?: "share failed"
            }
        }
    }

    /**
     * 【関数の役割】 画像 + 本文の共有インテントを投げる。X アプリがあればその投稿画面を直接開き、
     * 無ければ共有シート（ユーザーが送り先を選ぶ）。
     */
    private fun startImageShare(uri: Uri, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent(send).setPackage(X_PACKAGE))
            return
        } catch (e: ActivityNotFoundException) {
            // X アプリ未インストール → 共有シートへ。
        }
        try {
            startActivity(Intent.createChooser(send, null))
        } catch (e: ActivityNotFoundException) {
            // 共有先が 1 つも無い端末。何もしない。
        }
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

        /** X（旧 Twitter）アプリのパッケージ名。共有インテントの直接の送り先。 */
        const val X_PACKAGE = "com.twitter.android"

        /** 共有画像を置く cacheDir 配下のディレクトリ（res/xml/file_paths.xml と対）。 */
        const val SHARE_DIR = "share"
        const val SHARE_FILE_NAME = "beat-seeker-report.png"
    }
}
