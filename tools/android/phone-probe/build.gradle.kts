import java.util.Properties
import org.gradle.api.tasks.Sync

plugins { id("com.android.application") }
dependencies { testImplementation("junit:junit:4.13.2") }

val cardFixtureSource=project.file("../../../app/src/androidTest/assets/fixtures")
val stagedCardFixtureAssets=layout.buildDirectory.dir("generated/cardFixtureAssets")
val stageCardFixtureAssets=tasks.register<Sync>("stageCardFixtureAssets") {
    from(cardFixtureSource) { include("case1.jpg", "case2.jpg", "case3.jpg", "case4.jpg") }
    into(stagedCardFixtureAssets)
}

android {
    namespace="com.cyotun.jpq.phoneprobe"
    compileSdk=36
    defaultConfig {
        // Temporary replacement of the already authorized testing APK. The main
        // app is a different process and is never the instrumentation target.
        applicationId="com.cyotun.jpq.tests"
        minSdk=28
        targetSdk=36
        // Match Android's default version 0 for the normal instrumentation APK,
        // so restoring that APK does not require a version downgrade.
        versionName="1.0-local-preflight"
    }
    sourceSets.getByName("main").assets.srcDir(stagedCardFixtureAssets)
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

tasks.configureEach {
    if(name.startsWith("merge") && name.endsWith("Assets")) dependsOn(stageCardFixtureAssets)
}
