plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI 上由工作流把仓库 Secrets 里的 keystore 还原到某个路径，再用 DUO_KEYSTORE 指向它；
// 这样 CI 打出的包与本地 ./gradlew assembleDebug 的包同签名，可以直接覆盖安装。
// 本地构建时这个环境变量为空，签名行为与原来完全一致。
val ciKeystore: File? = System.getenv("DUO_KEYSTORE")?.let { File(it) }?.takeIf { it.exists() }

android {
    namespace = "com.duolauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.duolauncher"
        minSdk = 28
        targetSdk = 34
        versionCode = 136
        versionName = "1.4.1"
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = System.getenv("DUO_KEYSTORE_PASS") ?: "android"
                keyAlias = System.getenv("DUO_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DUO_KEY_PASS") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            signingConfig =
                if (ciKeystore != null) signingConfigs.getByName("ci")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
