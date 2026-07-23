import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing details, from an untracked keystore.properties or from the
 * environment (for CI). Absent both, the release build is simply left unsigned
 * rather than failing — a checkout without the key can still compile.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStorePath = signingValue("storeFile", "SSHMOBILE_STORE_FILE")

android {
    namespace = "com.j0ker.sshmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.j0ker.sshmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.10"
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                storeFile = file(releaseStorePath)
                storePassword = signingValue("storePassword", "SSHMOBILE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SSHMOBILE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SSHMOBILE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off deliberately: sshj resolves ciphers, key algorithms
            // and transports reflectively through its Factory service lists, and
            // a missing keep rule surfaces as a runtime handshake failure rather
            // than a build error. proguard-rules.pro holds the rules for whenever
            // this is turned on and properly tested against a real server.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // sshj + BouncyCastle ship duplicate service/licence metadata.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)

    // SSH.NET has no Android equivalent; sshj is the JVM/Android counterpart.
    implementation(libs.sshj) {
        // Android ships its own stripped BouncyCastle; pull the full one explicitly below.
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // sshj logs through SLF4J; silence it rather than drag in a backend.
    implementation(libs.slf4j.nop)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
