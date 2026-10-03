import java.util.Base64

plugins {
    id("com.android.application")
}

android {
    namespace = "com.threesverse.fbliveposter"
    compileSdk = 35

    defaultConfig {
        // v0.4.9: FRESH package identity — Play Protect caches its negative
        // verdict per PACKAGE name (cert change alone did not clear it), so a
        // new applicationId gives the app a clean first-scan slate.
        applicationId = "com.threesverse.liveposter"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.5.0"
    }

    signingConfigs {
        create("release") {
            // CI provides these via env (repo secrets FB_*); local builds fall
            // back to the debug key when the keystore is not available.
            val ksB64 = System.getenv("FB_KEYSTORE_B64")
            if (ksB64 != null) {
                val tmp = File.createTempFile("fbposter", ".keystore")
                tmp.writeBytes(Base64.getDecoder().decode(ksB64))
                tmp.deleteOnExit()
                storeFile = tmp
                storePassword = System.getenv("FB_STORE_PASS")
                keyAlias = System.getenv("FB_KEY_ALIAS")
                keyPassword = System.getenv("FB_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("FB_KEYSTORE_B64") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
}

base {
    archivesName.set("FB_Live_Group_Poster")
}
