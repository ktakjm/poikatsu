import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries.android)
}

// OSS ライセンス表示(#48)。ビルド時に Gradle 依存からメタデータ(R.raw.aboutlibraries)を自動生成する。
// Gradle 依存でない同梱コード(AppIcons.kt の material-design-icons 等)は config/libraries/ の
// カスタム定義で追加する(依存を増減してもこの設定は触らなくてよい)
aboutLibraries {
    collect {
        configPath = file("config")
    }
}

// APIキー/アプリIDは公開リポジトリにコミットしないため local.properties から読む(無ければ空)。
// 空のままでもビルドは通る(地図は表示されず YOLP は null を返す)= キー入手前のコード先行実装が可能。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY") ?: ""
val yolpAppId: String = localProps.getProperty("YOLP_APP_ID") ?: ""

android {
    namespace = "com.ktakjm.poikatsu"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ktakjm.poikatsu"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps SDK は AndroidManifest の meta-data からキーを読む(${MAPS_API_KEY} を差し込む)
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        // YOLP ローカルサーチの appid(YolpClient が BuildConfig.YOLP_APP_ID から読む)
        buildConfigField("String", "YOLP_APP_ID", "\"$yolpAppId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // 施策データはリポジトリ直下 data/・data-test/ を単一ソースとし、bundleDataAssets で
        // ディレクトリ構造ごと assets に同梱する(assets 内は data/xxx.json / data-test/xxx.json)。
        // AGP の SourceSet API は Provider を受け付けないため File に解決して渡す
        // (タスク依存は下の preBuild.dependsOn で明示している)
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/dataAssets").get().asFile)
        }
    }
}

// data/ と data-test/ は同名ファイルを含むため srcDir 直付けだと asset マージで衝突する。
// Sync でディレクトリ構造ごと生成ディレクトリへ集めてから同梱する(README 等の .md は除外)
val bundleDataAssets = tasks.register<Sync>("bundleDataAssets") {
    from(rootProject.file("data")) { into("data") }
    from(rootProject.file("data-test")) { into("data-test") }
    exclude("**/*.md")
    into(layout.buildDirectory.dir("generated/dataAssets"))
}
tasks.named("preBuild") { dependsOn(bundleDataAssets) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}