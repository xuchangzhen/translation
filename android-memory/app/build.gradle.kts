plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("LINGUABRIDGE_ANDROID_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("LINGUABRIDGE_ANDROID_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("LINGUABRIDGE_ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("LINGUABRIDGE_ANDROID_KEY_PASSWORD").orNull
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

android {
    namespace = "com.linguabridge.memory"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.linguabridge.memory"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.5.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        if (
            !releaseKeystorePath.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            create("linguabridgeRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else if (releaseRequested) {
            throw GradleException("发布版需要 LinguaBridge Android 签名环境变量")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("linguabridgeRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
