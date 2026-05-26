import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val tmdbToken = localProperties.getProperty("TMDB_TOKEN", "")
val escapedTmdbToken = tmdbToken
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE", "")
val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it.isNotBlank() }

android {
    namespace = "com.tedbigham.stremiochannels"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tedbigham.stremiochannels"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TMDB_TOKEN", "\"$escapedTmdbToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("Release signing is not configured. Add RELEASE_* values to local.properties.")
            }
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
}

dependencies {
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime)
}
