package com.beatseeker.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.beatseeker.app.databinding.ActivityEagateLoginBinding

/**
 * 【クラスの役割】 e-amusement GATE のログイン画面を表示する。
 *
 * 取り込み用の非表示 WebView が未ログインを検知したときだけ開かれる。ログインが成立すると
 * Cookie は {@link CookieManager} 経由でアプリ全体の WebView に共有されるため、
 * 以降の取り込みは無操作で通る。Cookie が切れるまで再表示されない。
 *
 * KONAMI ID の認証情報はこの WebView（＝eagate 自身）にしか渡らない。アプリは保持も送信もしない。
 */
class EagateLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEagateLoginBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEagateLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CookieManager.getInstance().setAcceptCookie(true)

        binding.webLogin.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // ログイン導線（KONAMI ID 側のドメインを含む）はこの WebView 内で完結させる。
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // ログインページを抜けて eagate の通常ページに戻ったらログイン成功とみなす。
                    if (Eagate.isEagateUrl(url) && !Eagate.isLoginUrl(url)) {
                        CookieManager.getInstance().flush()
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
            }
            loadUrl(Eagate.LOGIN_URL)
        }
    }

    override fun onDestroy() {
        binding.webLogin.destroy()
        super.onDestroy()
    }
}
