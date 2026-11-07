plugins {
    id("com.android.application")
}

android {
    namespace = "com.fvocr.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fvocr.bridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        // 不混淆：Hook 点全部按字符串反射查找，关掉最省心。
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // 现代 Xposed API 的入口 / 作用域 / 配置都在 META-INF/xposed/ 下
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        // 纯后台 Xposed 模块没有 UI，release 构建不需要 lintVital
        checkReleaseBuilds = false
    }
}

dependencies {
    // 现代（libxposed）API，只参与编译，运行时由框架提供
    compileOnly("io.github.libxposed:api:102.0.0")
    // API 类带有 androidx 注解，编译期需要
    compileOnly("androidx.annotation:annotation:1.9.1")
}
