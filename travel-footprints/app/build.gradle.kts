plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.example.travelfootprints"; compileSdk = 35
 defaultConfig { applicationId = "com.example.travelfootprints"; minSdk = 29; targetSdk = 35; versionCode = 14; versionName = "0.14"; manifestPlaceholders["appLabel"] = "여행 발자국" }
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
 implementation("com.google.android.material:material:1.12.0")
}

