plugins {
    id("com.android.application")
}

android {
    namespace = "com.threesverse.fbliveposter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.threesverse.fbliveposter"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

base {
    archivesName.set("FB_Live_Group_Poster")
}
