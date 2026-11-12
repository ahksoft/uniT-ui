plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "ahk.unitool.gsm.terminal"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  // Keep the library on the same runnable Linux ARM64 NDK as the app module.
  ndkVersion = "25.2.9519653"

  defaultConfig {
    minSdk = 24

    ndk {
      abiFilters += "arm64-v8a"
    }

    externalNativeBuild {
      cmake {
        cFlags.addAll(
          listOf(
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-Os",
            "-fno-stack-protector",
          )
        )
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
}

dependencies {
  implementation("androidx.annotation:annotation:1.9.1")
  implementation("com.getkeepsafe.relinker:relinker:1.4.5")
}
