plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "app.timeguard"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.timeguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    // CI에서는 저장소 Secret의 debug.keystore로 서명해서, 다음 버전도 지우지 않고 덮어 설치되게 한다.
    System.getenv("TG_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = file(path); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
