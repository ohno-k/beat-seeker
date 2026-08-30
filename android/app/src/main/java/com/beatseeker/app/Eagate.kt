package com.beatseeker.app

import android.net.Uri

/**
 * 【オブジェクトの役割】 eagate / beat-seeker のオリジン判定をまとめる。
 *
 * セキュリティ上の要: ネイティブ API（`BeatSeekerNative`）を露出してよいのは beat-seeker の
 * ページだけで、eagate や外部サイトには絶対に露出させてはならない。逆に収集スクリプトを
 * 注入してよいのは eagate だけである。判定ロジックを 1 箇所に集約して取り違いを防ぐ。
 */
object Eagate {

    /** eagate のホスト名。 */
    const val HOST = "p.eagate.573.jp"

    /** WebMessageListener に渡す許可オリジン。 */
    val ALLOWED_ORIGIN_RULES: Set<String> = setOf("https://$HOST")

    /** eagate のログインページ。未ログイン時はここへリダイレクトされる。 */
    const val LOGIN_URL = "https://$HOST/gate/p/login.html"

    /** beat-seeker 本体のホスト名（`BuildConfig.APP_URL` から導出）。 */
    val appHost: String = Uri.parse(BuildConfig.APP_URL).host ?: "beat-seeker.com"

    /** 【関数の役割】 URL が beat-seeker 自身（＝ネイティブ API を露出してよい相手）か。 */
    fun isAppUrl(url: String?): Boolean = hostOf(url)?.let {
        it == appHost || it.endsWith(".$appHost")
    } ?: false

    /** 【関数の役割】 URL が eagate 上のページか。 */
    fun isEagateUrl(url: String?): Boolean = hostOf(url) == HOST

    /**
     * 【関数の役割】 URL が eagate のログイン系ページか（＝未ログインでリダイレクトされた状態か）。
     * eagate は未ログインで djdata を開くと `/gate/p/login.html` 系へ飛ばす。
     */
    fun isLoginUrl(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        if (uri.host != HOST) return false
        val path = uri.path ?: return false
        return path.startsWith("/gate/p/login") || path.startsWith("/gate/p/common/login")
    }

    /** 【関数の役割】 URL からホスト名を取り出す。null や不正な URL は null を返す。 */
    private fun hostOf(url: String?): String? {
        if (url == null) return null
        return try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }
    }
}
