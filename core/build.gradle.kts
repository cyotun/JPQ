plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("net.i2p.crypto:eddsa:0.3.0"); testImplementation(kotlin("test-junit")) }
