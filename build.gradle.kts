plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "ru.vaulttracker"
val legacy = providers.gradleProperty("legacy").getOrElse("false").toBoolean()
val serverApi = if (legacy) "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT" else "dev.folia:folia-api:26.1.2.build.8-stable"
version = if (legacy) "0.13.0-preview-folia1.21.11" else "0.13.0-preview-folia26"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(serverApi)
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.h2database:h2:2.3.232")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(providers.gradleProperty("testFoliaApi").map { "dev.folia:folia-api:$it" }.getOrElse(serverApi))
    testImplementation("org.mockito:mockito-core:5.23.0")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(if (legacy) 21 else 25)) }
tasks.processResources {
    inputs.property("pluginVersion", project.version.toString())
    inputs.property("legacy", legacy)
    filesMatching("plugin.yml") {
        filter { line -> when {
            line.startsWith("version:") -> "version: ${project.version}"
            line.startsWith("api-version:") -> "api-version: '${if (legacy) "1.21.11" else "26.1.2"}'"
            else -> line
        } }
    }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    relocate("okhttp3", "ru.vaulttracker.libs.okhttp3")
    relocate("okio", "ru.vaulttracker.libs.okio")
    relocate("kotlin", "ru.vaulttracker.libs.kotlin")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.build { dependsOn(tasks.shadowJar) }

// A separate test-only plugin; never install this on the user's server.
val smoke by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("smokeJar") {
    archiveClassifier.set("smoke")
    from(smoke.output)
}
