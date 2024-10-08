package io.deepmedia.tools.grease

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFails
import kotlin.test.assertFalse

class GeneratedPomFileTest  {

    private var testProjectDir = createTempDirectory("tmp")
    private lateinit var settingsFile: Path
    private lateinit var buildFile: Path

    @BeforeTest
    fun setup() {
        settingsFile = testProjectDir.resolve("settings.gradle.kts")
        buildFile = testProjectDir.resolve("build.gradle.kts")
    }

    @Test
    fun test() {
        buildFile.writeText(
            """
                plugins {
                    `maven-publish`
                    id("com.android.library") version "8.1.4"
                }
                
                apply<io.deepmedia.tools.grease.GreasePlugin>()

                android {
                    namespace = "io.deepmedia.tools.grease.sample"
                    compileSdk = 34
                    defaultConfig {
                        minSdk = 21
                    }
                    publishing {
                        singleVariant("debug") {
                            withSourcesJar()
                        }
                    }
                }
                
                repositories {
                    google()
                    gradlePluginPortal()
                    mavenCentral()
                }

                publishing {
                    publications {
                        create("Test", MavenPublication::class.java) {
                            afterEvaluate {
                                from(components["debug"])
                            }
                        }
                    }
                }
                dependencies {
                    "grease"("com.otaliastudios:cameraview:2.7.2")
                    "greaseTree"("androidx.core:core:1.0.0")
                }
            """.trimIndent()
        )

        settingsFile.writeText("""
            pluginManagement {
                repositories {
                    google()
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            
            rootProject.name = "Sample"
        """.trimIndent())

        GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(testProjectDir.toFile())
            .forwardOutput()
            .withArguments("generatePomFileForTestPublication")
            .build()

        val pomContent = testProjectDir.resolve("build/publications/Test/pom-default.xml").readText()
        assertFalse(pomContent.contains("<groupId>androidx.core</groupId>") )
        assertFalse(pomContent.contains("<groupId>com.otaliastudios</groupId>") )
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun teardown() {
        testProjectDir.deleteRecursively()
    }
}