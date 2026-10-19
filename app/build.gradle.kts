import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing for release builds.
// Priority: keystore.properties (local) -> environment variables (CI) -> unsigned.
// When credentials are missing the release build still succeeds (unsigned),
// so regular CI checks on pull requests keep working without secrets.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

// CI provides the keystore itself base64-encoded; decode it to a temp file.
val ciKeystoreB64 = env("KEYSTORE_BASE64")
val ciKeystoreFile: File? = ciKeystoreB64?.let { b64 ->
    val f = File.createTempFile("release", ".jks")
    f.deleteOnExit()
    f.writeBytes(Base64.getDecoder().decode(b64))
    f
}

// Local path resolves relative to the repo root; CI uses the decoded temp file.
val signingStoreFile: File? = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
    ?: ciKeystoreFile
val signingStorePassword: String? = keystoreProps.getProperty("storePassword") ?: env("KEYSTORE_PASSWORD")
val signingKeyAlias: String? = keystoreProps.getProperty("keyAlias") ?: env("KEY_ALIAS")
val signingKeyPassword: String? = keystoreProps.getProperty("keyPassword") ?: env("KEY_PASSWORD")

android {
    namespace = "com.example.hidegames"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.hidegames"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
    }
    signingConfigs {
        if (signingStoreFile != null && signingStorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        release {
            // Keep minify off: R8 could rename/strip MainHook, which is referenced
            // by name from assets/xposed_init (legacy Xposed entry point).
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { compileOnly("de.robv.android.xposed:api:82") }
