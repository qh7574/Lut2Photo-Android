plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

android {
    namespace = "cn.alittlecookie.lut2photo.lut2photo"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.alittlecookie.lut2photo.lut2photo"
        minSdk = 31
        targetSdk = 36
        versionCode = 10005
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true

        // 添加OpenGL ES支持
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    buildToolsVersion = "35.0.0"
}

dependencies {
    // Kotlin 标准库 - 解决 NoClassDefFoundError 问题
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.exifinterface)
    
    // 新增依赖 - 使用正确的库
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android.v1102)
    implementation(libs.androidx.lifecycle.service.v292)

    // GPU相关依赖
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    // 水印处理相关依赖
    implementation(libs.coil)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
