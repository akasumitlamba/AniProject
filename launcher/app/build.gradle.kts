import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Only runtime image assets are copied. Keep the required offline catalog as an explicit raw
// resource, rather than exposing repository documentation or provenance/build files as assets.
val bannerAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../banners")) { include("**/*.webp") }
    into(layout.buildDirectory.dir("generated/bannerAssets"))
}
val bannerMetadata by tasks.registering(Sync::class) {
    from(rootProject.file("../banners/catalog.json")) { into("raw"); rename { "banner_catalog.json" } }
    into(layout.buildDirectory.dir("generated/bannerResources"))
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

android {
    namespace = "app.anihome.launcher"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.anihome.launcher"
        testInstrumentationRunner = "app.anihome.launcher.RegressionInstrumentation"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "0.9.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val localSigning = Properties().apply {
            val signingFile = rootProject.file("keystore.properties")
            if (signingFile.exists()) signingFile.inputStream().use { load(it) }
        }
        val storePath = System.getenv("ANIHOME_KEYSTORE") ?: localSigning.getProperty("storeFile")
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = System.getenv("ANIHOME_KEYSTORE_PASSWORD") ?: localSigning.getProperty("storePassword", "")
                keyAlias = System.getenv("ANIHOME_KEY_ALIAS") ?: localSigning.getProperty("keyAlias", "anihome")
                keyPassword = System.getenv("ANIHOME_KEY_PASSWORD") ?: localSigning.getProperty("keyPassword", storePassword)
            }
        }
    }

    buildTypes {
        release {
            // Public artifacts must not embed private repository provenance.
            vcsInfo { include = false }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { viewBinding = true; aidl = true }

    sourceSets.getByName("main").assets.srcDir(bannerAssets.map { it.destinationDir })
    sourceSets.getByName("main").res.srcDir(bannerMetadata.map { it.destinationDir })

    splits {
        abi {
            isEnable = false
        }
    }
}

tasks.named("preBuild") { dependsOn(bannerAssets, bannerMetadata) }

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
