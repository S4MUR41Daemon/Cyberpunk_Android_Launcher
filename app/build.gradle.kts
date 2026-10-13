import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ── Firma compartida ATMOS ──────────────────────────────────────────────
// La misma clave firma ATMOS y el launcher → el permiso de firma que los conecta funciona.
// En GitHub Actions llega por variables de entorno (secretos); en el PC, local.properties apunta
// al fichero keystore.properties (fuera del repo). Si no hay clave, se usa la debug como antes.
val atmosSigning: Map<String, String>? = run {
    System.getenv("ATMOS_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
        return@run mapOf(
            "storeFile" to path,
            "storePassword" to System.getenv("ATMOS_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("ATMOS_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("ATMOS_KEY_PASSWORD").orEmpty()
        )
    }
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { local.load(it) }
    val f = local.getProperty("atmos.keystore.props")?.let { file(it) }?.takeIf { it.exists() } ?: return@run null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    p.stringPropertyNames().associateWith { p.getProperty(it) }
}

android {
    namespace = "com.atmos.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atmos.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Redirección del login de Spotify: atmos-launcher://spotify-callback
        manifestPlaceholders["redirectSchemeName"] = "atmos-launcher"
        manifestPlaceholders["redirectHostName"] = "spotify-callback"
        manifestPlaceholders["redirectPathPattern"] = ".*"
    }

    signingConfigs {
        if (atmosSigning != null) create("atmos") {
            storeFile = file(atmosSigning.getValue("storeFile"))
            storePassword = atmosSigning.getValue("storePassword")
            keyAlias = atmosSigning.getValue("keyAlias")
            keyPassword = atmosSigning.getValue("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (atmosSigning != null) signingConfigs.getByName("atmos") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.profileinstaller)

    // NEURO//AUDIO: SDK oficial de Spotify (App Remote = control de la app oficial; auth = login) + Web API.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.spotify.auth)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
