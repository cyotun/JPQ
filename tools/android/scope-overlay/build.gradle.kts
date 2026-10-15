import java.util.Properties

plugins { id("com.android.application") }
android {
    namespace="com.cyotun.jpq.scopeoverlay"
    compileSdk=36
    defaultConfig {
        applicationId="com.cyotun.jpq.scopeoverlay"
        minSdk=28
        targetSdk=36
        versionName="1.0-local-scope-overlay"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    val identityFile=rootProject.file(".signing/release.properties")
    if(identityFile.exists()) {
        val identity=Properties().apply { identityFile.inputStream().use { load(it) } }
        signingConfigs.create("personal") {
            storeFile=rootProject.file(identity.getProperty("storeFile")); storePassword=identity.getProperty("storePassword")
            keyAlias=identity.getProperty("keyAlias"); keyPassword=identity.getProperty("keyPassword")
        }
        buildTypes.getByName("debug").signingConfig=signingConfigs.getByName("personal")
    }
}
