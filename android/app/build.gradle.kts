plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * beat-seeker 本体の URL。`-PappUrl=...` で上書きできる（ローカル開発サーバ検証用）。
 */
val appUrl: String = (project.findProperty("appUrl") as String?) ?: "https://beat-seeker.com"

/**
 * 収集スクリプトの URL。既定では `appUrl` から導出するので、通常は `-PappUrl` だけ指定すればよい。
 */
val scraperScriptUrl: String =
    (project.findProperty("scraperScriptUrl") as String?) ?: "$appUrl/native-scraper.js"

android {
    namespace = "com.beatseeker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.beatseeker.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 表示する beat-seeker の URL と、eagate に注入する収集スクリプトの URL。
        // スクリプトはアプリに同梱せず実行時に取得する。eagate の HTML 構造が変わっても
        // Web 側のデプロイだけで追従でき、アプリの再リリースが不要になるため。
        //
        // ローカルの開発サーバに向けたい場合はビルド時に上書きできる:
        //   ./gradlew assembleDebug -PappUrl=http://192.168.1.10:5173
        // （http:// を使う場合の cleartext 許可は debug ビルドのマニフェストで入れてある）
        buildConfigField("String", "APP_URL", "\"$appUrl\"")
        buildConfigField("String", "SCRAPER_SCRIPT_URL", "\"$scraperScriptUrl\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            // 端末に本番版と並べて入れられるようにする（動作比較・切り戻しのため）。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebMessageListener（オリジンを限定した WebView ↔ ネイティブ通信）に必要
    implementation("androidx.webkit:webkit:1.11.0")
}
