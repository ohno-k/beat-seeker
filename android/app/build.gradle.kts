plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
        buildConfigField("String", "APP_URL", "\"https://beat-seeker.com\"")
        buildConfigField("String", "SCRAPER_SCRIPT_URL", "\"https://beat-seeker.com/native-scraper.js\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
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
