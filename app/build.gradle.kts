import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.cyotun.jpq"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cyotun.jpq"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    buildTypes { getByName("debug") { applicationIdSuffix = ".dev"; versionNameSuffix = "+debug" } }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity:1.12.3")
}
val checkOverlayFont by tasks.registering {
    doLast {
        val font = file("src/main/assets/overlay.ttf")
        check(font.exists()) { "Prepare the official overlay font locally; see app/README.md. Font binary is not published in Git." }
        val sha = MessageDigest.getInstance("SHA-256").digest(font.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == "6f6a1e15f33d559ff3d0d7dcc8bd9ab25a6dbd8e9bcd29865daf7615a17ea284") { "Overlay font does not match the verified typeface" }
    }
}
tasks.named("preBuild").configure { dependsOn(checkOverlayFont) }
