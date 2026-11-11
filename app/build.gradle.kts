import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autoad.closer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoad.closer"
        minSdk = 24
        targetSdk = 34

        // 讀取 GitHub Actions 自動累加的執行編號
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        // 修改為 v1.xxx 格式（例如 v1.12）
        versionName = "1.$runNumber"

        // 動態生成台北時間（年-月-日 時:分）
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
            timeZone = TimeZone.getTimeZone("GMT+8")
        }
        val buildTime = dateFormat.format(Date())

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
