// Ki.KS-JVM

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
version = "2.3.3"
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

tasks.register("javaPath") {
    doLast {
        println(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().executablePath.asFile.absolutePath)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
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
    manifest { attributes("Main-Class" to "io.kixi.ks.tools.ReplKt") }
    from(sourceSets["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}

// New tasks for supporting popular operating systems and shells

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  build.gradle.kts — Distribution Tasks for Ki Script
//
//  Add these tasks to your existing build.gradle.kts.
//  They create the installable distribution package.
//
//  Usage:
//      ./gradlew dist          Create distribution archive
//      ./gradlew installLocal  Install to ~/.ki directly
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Fat JAR (all dependencies bundled, hidden from user) ────────────────────

// NOTE: If you already have a `replJar` task, rename or merge with this.
// The key difference: the main class here is Run (for `ks`), while the
// REPL main class is used for `ksr`. Both are in the same JAR — the
// launcher scripts select the entry point via -cp + class name.

tasks.register<Jar>("runtimeJar") {
    group = "distribution"
    description = "Build the Ki Script runtime (fat JAR with all dependencies)"

    archiveBaseName.set("ks-runtime")
    archiveVersion.set("")  // no version suffix — just ks-runtime.jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Primary manifest for `java -jar` (defaults to script runner)
    manifest {
        attributes(
            "Main-Class" to "io.kixi.ks.Run",
            "Implementation-Title" to "Ki Script",
            // "Implementation-Version" pulled from Repl.VERSION at runtime
        )
    }

    // Bundle all runtime dependencies
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}

// ── Distribution Layout ─────────────────────────────────────────────────────

val distDir = layout.buildDirectory.dir("dist/ki-script")

tasks.register<Copy>("distLayout") {
    group = "distribution"
    description = "Assemble the distribution directory layout"
    dependsOn("runtimeJar")

    // Launcher scripts (Unix)
    from("packaging/bin/ks")   { into("bin") }
    from("packaging/bin/ksr")  { into("bin") }

    // Launcher scripts (Windows)
    from("packaging/bin/ks.cmd")  { into("bin") }
    from("packaging/bin/ksr.cmd") { into("bin") }

    // Runtime JAR
    from(tasks.named("runtimeJar").map { (it as Jar).archiveFile }) {
        into("lib")
        rename { "ks-runtime.jar" }
    }

    // Installers
    from("packaging/install.sh")
    from("packaging/install.ps1")

    // Version file
    doFirst {
        val ksVersion = project.property("ksVersion") as String
        file("${layout.buildDirectory.get()}/VERSION").writeText(ksVersion)
    }
    from(layout.buildDirectory.file("VERSION"))

    into(distDir)
}

tasks.named<ProcessResources>("processResources") {
    val ksVersion = project.property("ksVersion") as String
    doFirst {
        file("$destinationDir/ks-version.txt").writeText(ksVersion)
    }
}

// Make Unix scripts executable after copy
tasks.named("distLayout") {
    doLast {
        val binDir = distDir.get().dir("bin").asFile
        listOf("ks", "ksr").forEach { name ->
            val f = File(binDir, name)
            if (f.exists()) f.setExecutable(true, false)
        }
        val installSh = File(distDir.get().asFile, "install.sh")
        if (installSh.exists()) installSh.setExecutable(true, false)
    }
}

// ── Archive ─────────────────────────────────────────────────────────────────

tasks.register<Tar>("kiDist") {
    group = "distribution"
    description = "Create ki-script distribution archive (.tar.gz)"
    dependsOn("distLayout")

    archiveBaseName.set("ki-script")
    archiveVersion.set("")  // use VERSION file content instead
    compression = Compression.GZIP

    from(distDir)
    into("ki-script")
}

tasks.register<Zip>("kiDistZip") {
    group = "distribution"
    description = "Create ki-script distribution archive (.zip, for Windows)"
    dependsOn("distLayout")

    archiveBaseName.set("ki-script")
    archiveVersion.set("")

    from(distDir)
    into("ki-script")
}

// ── Local Install ───────────────────────────────────────────────────────────

tasks.register("installLocal") {
    group = "distribution"
    description = "Install Ki Script to ~/.ki (runs the installer)"
    dependsOn("distLayout")

    doLast {
        val distRoot = distDir.get().asFile
        val installer = File(distRoot, "install.sh")

        if (!installer.exists()) {
            throw GradleException("install.sh not found in distribution layout")
        }

        exec {
            workingDir = distRoot
            commandLine("sh", "install.sh")
        }
    }
}

// ── Dev REPL (preserves existing IDE workflow) ──────────────────────────────

// This is equivalent to your existing ksr.sh workflow.
// Run from terminal: ./gradlew -q --console=plain repl
tasks.register<JavaExec>("repl") {
    group = "application"
    description = "Launch the KS interactive REPL (development)"
    mainClass.set("io.kixi.ks.tools.ReplKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
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