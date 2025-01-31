import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

val ktorVersion = "3.0.3"
val log4jVersion = "2.24.3"
val kotlinVersion = "2.1.10"

plugins {
    kotlin("jvm") version "2.1.10"
    id("com.github.ben-manes.versions") version "0.52.0"
    application
}

group = "at.rueckgr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    implementation("org.slf4j:slf4j-api:2.0.16")
    api("org.apache.logging.log4j:log4j-api:$log4jVersion")
    implementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.auth0:java-jwt:4.5.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {
    gradleReleaseChannel = "current"
}

tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        candidate.version.lowercase().contains("alpha") ||
                candidate.version.lowercase().contains("beta") ||
                candidate.version.lowercase().contains("rc")
    }
}

distributions {
    main {
        version = "latest"
    }
}

application {
    mainClass.set("at.rueckgr.MainKt")
}
