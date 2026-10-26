import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties in the project root (git-ignored; see
// docs/release.md), with the passwords optionally from environment variables. Without it the
// release build is left unsigned. The key itself never goes into the repository.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.anupshrestha.pairloom"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anupshrestha.pairloom"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        val storePath = keystoreProperties.getProperty("storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("PAIRLOOM_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("PAIRLOOM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking and optimisation, and unused resources removed.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Release code, not debuggable, signed with the debug key: for measuring CPU and battery on
        // the test phones (a debuggable build runs largely in the interpreter). Not for publishing.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// The Compose group-mapping file only improves Compose stack traces in the Play Console; producing
// it needs an extra artifact that offline builds do not have.
composeCompiler {
    includeComposeMappingFile.set(false)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
