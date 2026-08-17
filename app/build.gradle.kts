plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.adil.chatapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adil.chatapp"
        minSdk = 24
        targetSdk = 34
        // Bumped from 1/"1.0": Samsung's launcher (One UI Home) and its icon
        // cache key entries off (packageName, signature, versionCode). Since
        // every previous "update" kept versionCode = 1, some OEM caching
        // layers may have treated re-installs as "the same version already
        // known" and reused a cached icon bitmap instead of reading the new
        // one — even though the APK's actual resources were correct (verified
        // by extracting the built app-release.apk directly and confirming the
        // new icon is present at every density). Bumping this forces those
        // caches to recognize a genuinely new version.
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        // A fixed debug keystore committed to the repo, so every CI build is
        // signed with the SAME key. Without this, GitHub Actions generates a
        // new random debug key on every run, and Android refuses to install
        // an update over an app signed with a different key ("problem
        // parsing the package" / must uninstall the old app first).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Reuse the SAME committed keystore as debug so a release build
            // installs cleanly over a previously-installed debug build (same
            // signing identity = no "uninstall first" prompt). A real Play
            // Store release would use a separate, private release key, but
            // that's not needed for direct-APK distribution like this.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
