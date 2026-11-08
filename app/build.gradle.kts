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
        versionCode = 4
        versionName = "2.0.2"
        // libmiocr.so 只有 arm64-v8a，jniLibs 里也只放这一套，
        // 不显式 abiFilters，让 AGP 按目录自然只打 64 位。
    }

    buildTypes {
        // 不混淆：Hook 点按字符串反射查找；OCREngine / OCRData 的类名与字段名
        // 更被 JNI 按字符串 FindClass / GetFieldID 硬编码，混淆会直接崩。
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
        jniLibs {
            // 引擎会按路径 dlopen 并在 cacheDir 写 OpenCL 编译缓存，
            // 传统解压方式（so 落到 nativeLibraryDir）最稳妥。
            useLegacyPackaging = true
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
