plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test-junit")) }
tasks.test { testLogging { events("passed", "skipped", "failed") } }
