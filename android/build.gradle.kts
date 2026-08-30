// ルートの build スクリプト。プラグインのバージョンだけを宣言し、適用は :app 側で行う。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
