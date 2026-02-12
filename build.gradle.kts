// import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    `java-library`
    `maven-publish`
    application  // Just the plugin ID here - nothing else!
    id("org.jetbrains.dokka") version "2.1.0"
    signing
}

group = "io.kixi"
version = "2.3.2"
description = "ki-ks"

// Application config goes HERE - outside plugins block
application {
    mainClass.set("AppKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // === Ki Dependencies (resolved via composite builds — see settings.gradle.kts) ===
    implementation("io.kixi:Ki.Core-JVM:2.3.2")
    implementation("io.kixi:Ki.KD-JVM:2.3.2")

    // === JLine (REPL terminal handling) ===
    implementation("org.jline:jline:3.26.3")

    // === Kotlin Reflection (full library) ===
    implementation(kotlin("reflect"))

    // === Kotest (testing) ===
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.kotest:kotest-property:6.0.7")
}

// ============================================================================
// JVM Configuration
// ============================================================================

kotlin {
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(false)
    }
}

java {
    withSourcesJar()
}

// ============================================================================
// Testing (Kotest runs on JUnit Platform)
// ============================================================================

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// ============================================================================
// Run Configuration - also outside plugins block!
// ============================================================================

tasks.named<JavaExec>("run") {
    workingDir = project.projectDir
    standardInput = System.`in`
}

// ============================================================================
// Documentation (Dokka 2.1.0 — v2 API)
// ============================================================================

val dokkaJavadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.named("dokkaGenerate"))
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

// ============================================================================
// REPL
// ============================================================================

/** Fat JAR containing the KS REPL and all dependencies. */
tasks.register<Jar>("replJar") {
    archiveFileName.set("ks-repl.jar")
    manifest { attributes("Main-Class" to "io.kixi.ks.repl.ReplKt") }
    from(sourceSets["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}

/**
 * Build and launch the KS REPL.  Usage: ./gradlew -q repl
 * Must be run from a real terminal (not IntelliJ's Run panel).
 */
/*
tasks.register("repl") {
    dependsOn("replJar")
    doLast {
        val jar = layout.buildDirectory.file("libs/ks-repl.jar").get().asFile
        ProcessBuilder("java", "-jar", jar.absolutePath)
            .inheritIO()
            .start()
            .waitFor()
    }
}
*/

// ============================================================================
// Publishing
// ============================================================================

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kixi-io/Ki.KS-JVM")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(dokkaJavadocJar)

            pom {
                groupId = "${project.group}"
                artifactId = "Ki.KS-JVM"
                name.set("Ki.KS")
                description.set("A JVM implementation of Ki.KS (Ki Script)")
                url.set("https://kixi.io")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://raw.githubusercontent.com/kixi-io/Ki.KS-JVM/master/LICENSE")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("dleuck")
                        name.set("Daniel Leuck")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/kixi-io/Ki.KS-JVM.git")
                    developerConnection.set("scm:git:ssh://github.com/kixi-io/Ki.KS-JVM.git")
                    url.set("https://github.com/kixi-io/Ki.KS-JVM/")
                }
            }
        }
    }
}

// ============================================================================
// Convenience Tasks
// ============================================================================

tasks.register("buildAll") {
    description = "Builds JAR, sources JAR, and Javadoc JAR"
    dependsOn("jar", "sourcesJar", "dokkaJavadocJar")
}