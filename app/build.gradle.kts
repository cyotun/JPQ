import java.security.MessageDigest
import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.cyotun.jpq"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cyotun.jpq"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.4-regression"
        testApplicationId = "com.cyotun.jpq.tests"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    testBuildType = "release"
    val identityFile=rootProject.file(".signing/release.properties")
    if(identityFile.exists()) {
        val identity=Properties().apply { identityFile.inputStream().use { load(it) } }
        signingConfigs.create("personal") {
            storeFile=rootProject.file(identity.getProperty("storeFile")); storePassword=identity.getProperty("storePassword")
            keyAlias=identity.getProperty("keyAlias"); keyPassword=identity.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig=signingConfigs.getByName("personal")
    }
    buildTypes { getByName("debug") { applicationIdSuffix = ".dev"; versionNameSuffix = "+debug" } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity:1.12.3")
    implementation("com.google.ai.edge.litert:litert:2.2.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
val checkOverlayFont by tasks.registering {
    doLast {
        val font = file("src/main/assets/overlay.ttf")
        check(font.exists()) { "Prepare the official overlay font locally; see app/README.md. Font binary is not published in Git." }
        val sha = MessageDigest.getInstance("SHA-256").digest(font.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == "6f6a1e15f33d559ff3d0d7dcc8bd9ab25a6dbd8e9bcd29865daf7615a17ea284") { "Overlay font does not match the verified typeface" }
    }
}
val checkLocalModel by tasks.registering {
    doLast {
        val model=file("src/main/assets/models/cards.tflite")
        check(model.exists()) { "Prepare the local authorized detector asset with tools/android/prepare-model.ps1; it is not distributed in Git." }
        val sha=MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha=="87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c") { "Unexpected detector asset" }
    }
}
tasks.named("preBuild").configure { dependsOn(checkOverlayFont,checkLocalModel) }
