plugins {
    id("com.android.application")
}

android {
    namespace = "com.deepseekdemo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deepseekdemo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 鑷敤绛惧悕锛歞ebug 绛惧悕鍗冲彲锛屼究浜庣洿鎺ュ畨瑁?            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
