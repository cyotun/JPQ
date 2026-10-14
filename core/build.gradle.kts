plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("net.i2p.crypto:eddsa:0.3.0"); testImplementation(kotlin("test-junit")) }
tasks.register<JavaExec>("replayPhoneVideo") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.cyotun.jpq.core.PhoneVideoReplayKt")
    val tape=providers.gradleProperty("replayTape")
    val output=providers.gradleProperty("replayOutput")
    doFirst {
        args(tape.get(),output.get())
        providers.gradleProperty("replayTrace").orNull?.let { args(it) }
    }
}
tasks.register<JavaExec>("replayLoggedFrames") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.cyotun.jpq.core.PhoneLoggedFrameReplayKt")
    doFirst { args(providers.gradleProperty("loggedTape").get(),providers.gradleProperty("loggedOutput").get()) }
}
tasks.register<JavaExec>("probeSuitSymbols") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.cyotun.jpq.core.SuitSymbolFixtureProbeKt")
    doFirst {
        args(providers.gradleProperty("suitAnnotations").get())
        providers.gradleProperty("suitProbeOutput").orNull?.let { args(it) }
    }
}
tasks.register<JavaExec>("probeGradeHeaders") {
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.cyotun.jpq.core.TencentRoundGradeFixtureProbeKt")
    doFirst { args(providers.gradleProperty("gradeAnnotations").get()) }
}
