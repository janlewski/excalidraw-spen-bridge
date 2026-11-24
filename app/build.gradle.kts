import java.util.Base64

plugins {
    id("com.android.application")
}

fun gitShortSha(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && text.isNotEmpty()) text else "unknown"
} catch (_: Exception) {
    "unknown"
}

android {
    namespace = "dev.excalidraw.spenbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.excalidraw.spenbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = ((findProperty("versionCode") as String?) ?: "2").toInt()
        versionName = (findProperty("versionName") as String?) ?: "0.2.0-diag"
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
        buildConfigField("int", "PROTO", "1")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
    if (!keystoreBase64.isNullOrBlank()) {
        val keystoreFile = layout.buildDirectory.file("ci-keystore.jks").get().asFile
        keystoreFile.parentFile.mkdirs()
        keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
        signingConfigs {
            create("ciRelease") {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystoreBase64.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
