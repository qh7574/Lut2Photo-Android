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
        minSdk = 29
        targetSdk = 36
        versionCode = 100025
        versionName = "2.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true

        // NDK配置
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Native构建配置
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-31"
                )
            }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    buildToolsVersion = "35.0.0"

    // Native构建配置
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 打包配置
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("**/libjsc.so")
        }
    }
    ndkVersion = "29.0.13846066 rc3"
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

    // 颜色选择器依赖
    implementation("me.jfenn.ColorPickerDialog:base:0.2.2")
    implementation("me.jfenn.ColorPickerDialog:imagepicker:0.2.2")

    // Android Palette库用于从图片提取颜色
    implementation("androidx.palette:palette-ktx:1.0.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
