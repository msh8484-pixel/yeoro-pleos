import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
// Supabase URL·publishable 키는 git에 넣지 않고 local.properties에서 BuildConfig로 넣는다 (키가 없으면 "" → 클라우드 꺼짐)
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun localProp(k: String): String = localProps.getProperty(k)?.trim().orEmpty()
android {
    namespace = "ai.yeoro"
    compileSdk = 37
    defaultConfig {
        applicationId = "ai.yeoro"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1.0"
        buildConfigField("String", "SUPABASE_URL", "\"${localProp("yeoro.supabase.url").ifEmpty { "https://dghpdvpkgvamsaidndqa.supabase.co" }}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${localProp("yeoro.supabase.key")}\"")
    }
    // release 서명 키는 git 밖(local.properties의 yeoro.release.store·password). 없으면 서명 없는 release가 나온다
    val releaseStore = localProp("yeoro.release.store")
    signingConfigs {
        if (releaseStore.isNotEmpty()) create("release") {
            storeFile = file(releaseStore); storePassword = localProp("yeoro.release.password")
            keyAlias = "yeoro"; keyPassword = localProp("yeoro.release.password")
        }
    }
    buildTypes { release { if (releaseStore.isNotEmpty()) signingConfig = signingConfigs.getByName("release") } }
    buildFeatures { compose = true; buildConfig = true }
    useLibrary("android.car")
    testOptions { unitTests.isIncludeAndroidResources = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
}
