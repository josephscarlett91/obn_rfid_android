plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

android {
  namespace = "com.gti.rfid"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.gti.rfid"
    minSdk = 23
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
    debug { isMinifyEnabled = false }
  }

  buildFeatures { compose = true }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.activity.compose)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.material3)

  debugImplementation(libs.compose.ui.tooling)
}
