import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties in the repo root (gitignored) or, in CI,
// from II_KEYSTORE_FILE / II_KEYSTORE_PASSWORD / II_KEY_ALIAS / II_KEY_PASSWORD. Without
// either, release builds fall back to the debug key so contributors can still build.
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signing(prop: String, env: String): String? = signingProps.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "dev.ii.tools"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.stelnet.tools"
        minSdk = 31 // Material You dynamic colour
        targetSdk = 37
        versionCode = 10500 // major * 10000 + minor * 100 + patch
        versionName = "1.5.0"
    }

    val releaseSigning = signing("storeFile", "II_KEYSTORE_FILE")?.let { file ->
        signingConfigs.create("release") {
            storeFile = file(file)
            storePassword = signing("storePassword", "II_KEYSTORE_PASSWORD")
            keyAlias = signing("keyAlias", "II_KEY_ALIAS")
            keyPassword = signing("keyPassword", "II_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
        // A debuggable build that installs beside the release app (its own package, its own
        // pairing), for testing on a phone whose release build is signed elsewhere.
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += "debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The Shizuku user service's interface (shell/IShellStats.aidl).
        aidl = true
        // BuildConfig.DEBUG: the stream's once-a-second logs only on debuggable builds.
        buildConfig = true
    }

    packaging {
        // Apache SSHD's jars each carry the same license metadata.
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.play.services.code.scanner)
    implementation(libs.apache.sshd.core)
    implementation(libs.apache.sshd.sftp)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.compose.ui.tooling)
}
