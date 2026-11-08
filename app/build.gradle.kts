plugins {
    id("com.android.application")
}

android {
    namespace = "cc.linho.yangshipin.mediasession"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    enableKotlin = false

    defaultConfig {
        applicationId = "cc.linho.yangshipin.mediasession"
        minSdk = 28
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs["debug"]
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
