plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("net.i2p.crypto:eddsa:0.3.0"); testImplementation(kotlin("test-junit")) }
tasks.register<JavaExec>("replayPhoneVideo") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.cyotun.jpq.core.PhoneVideoReplayKt")
    val tape=providers.gradleProperty("replayTape")
    val output=providers.gradleProperty("replayOutput")
    doFirst { args(tape.get(),output.get()) }
}
