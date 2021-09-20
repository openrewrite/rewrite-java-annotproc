import nebula.plugin.info.InfoBrokerPlugin
import nl.javadude.gradle.plugins.license.LicenseExtension
import java.util.*

buildscript {
    repositories {
        jcenter()
        gradlePluginPortal()
    }

    dependencies {
        classpath("io.spring.gradle:spring-release-plugin:0.20.1")

        constraints {
            classpath("org.jfrog.buildinfo:build-info-extractor-gradle:4.13.0") {
                because("Need recent version for Gradle 6+ compatibility")
            }
        }
    }
}

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm") version "1.4.20"
    id("io.spring.release") version "0.20.1"
}

apply(plugin = "license")
apply(plugin = "nebula.maven-resolved-dependencies")
apply(plugin = "nebula.maven-publish")

group = "org.openrewrite"

repositories {
    mavenLocal()
    maven { url = uri("https://dl.bintray.com/openrewrite/maven") }
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<Javadoc> {
    exclude("**/RewriteAnnotationProcessor**")
}

dependencies {
    implementation("org.openrewrite:rewrite-core:latest.integration")
    implementation("org.openrewrite:rewrite-java-11:latest.integration")

    testImplementation("org.jooq:joor:latest.release")

    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("org.openrewrite:rewrite-test:latest.release")
    testImplementation("org.assertj:assertj-core:latest.release")

    testImplementation("com.google.auto.service:auto-service:latest.release")

    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.0.13")
}

tasks.named<JavaCompile>("compileJava") {
    sourceCompatibility = JavaVersion.VERSION_11.toString()
    targetCompatibility = JavaVersion.VERSION_11.toString()

    options.compilerArgs.clear() // remove `--release 8` set in root gradle build
    options.compilerArgs.addAll(listOf(
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED"
    ))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames")
}

configure<LicenseExtension> {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = project.rootProject.file("gradle/licenseHeader.txt")
    mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
    strictCheck = true
}

configure<PublishingExtension> {
    publications {
        named("nebula", MavenPublication::class.java) {
            suppressPomMetadataWarningsFor("runtimeElements")

            pom.withXml {
                (asElement().getElementsByTagName("dependencies").item(0) as org.w3c.dom.Element).let { dependencies ->
                    dependencies.getElementsByTagName("dependency").let { dependencyList ->
                        var i = 0
                        var length = dependencyList.length
                        while (i < length) {
                            (dependencyList.item(i) as org.w3c.dom.Element).let { dependency ->
                                if ((dependency.getElementsByTagName("scope")
                                                .item(0) as org.w3c.dom.Element).textContent == "provided") {
                                    dependencies.removeChild(dependency)
                                    i--
                                    length--
                                }
                            }
                            i++
                        }
                    }
                }
            }
        }
    }
}

tasks.withType<GenerateMavenPom> {
    doLast {
        // because pom.withXml adds blank lines
        destination.writeText(
                destination.readLines().filter { it.isNotBlank() }.joinToString("\n")
        )
    }

    doFirst {
        val runtimeClasspath = configurations.getByName("runtimeClasspath")

        val gav = { dep: ResolvedDependency ->
            "${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}"
        }

        val observedDependencies = TreeSet<ResolvedDependency> { d1, d2 ->
            gav(d1).compareTo(gav(d2))
        }

        fun reduceDependenciesAtIndent(indent: Int):
                (List<String>, ResolvedDependency) -> List<String> =
                { dependenciesAsList: List<String>, dep: ResolvedDependency ->
                    dependenciesAsList + listOf(" ".repeat(indent) + dep.module.id.toString()) + (
                            if (observedDependencies.add(dep)) {
                                dep.children
                                        .sortedBy(gav)
                                        .fold(emptyList(), reduceDependenciesAtIndent(indent + 2))
                            } else {
                                // this dependency subtree has already been printed, so skip it
                                emptyList()
                            }
                            )
                }

        project.plugins.withType<InfoBrokerPlugin> {
            add("Resolved-Dependencies", runtimeClasspath
                    .resolvedConfiguration
                    .lenientConfiguration
                    .firstLevelModuleDependencies
                    .sortedBy(gav)
                    .fold(emptyList(), reduceDependenciesAtIndent(6))
                    .joinToString("\n", "\n", "\n" + " ".repeat(4)))
        }
    }
}
