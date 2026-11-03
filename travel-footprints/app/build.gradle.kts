plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.example.travelfootprints"; compileSdk = 35
 defaultConfig { applicationId = "com.example.travelfootprints"; minSdk = 29; targetSdk = 35; versionCode = 25; versionName = "0.25"; manifestPlaceholders["appLabel"] = "여행 발자국"; ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") } }
 // CI에서는 집 PC의 debug.keystore를 명시적으로 지정한다(TF_DEBUG_KEYSTORE). 없으면 기본 debug 키.
 System.getenv("TF_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
  signingConfigs.getByName("debug") { storeFile = file(path); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
 }
 buildFeatures { viewBinding = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.appcompat:appcompat:1.7.0")
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("androidx.exifinterface:exifinterface:1.3.7")
 implementation("org.maplibre.gl:android-sdk:11.8.2")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 implementation("com.google.android.material:material:1.12.0")
 implementation("androidx.recyclerview:recyclerview:1.3.2")
}

