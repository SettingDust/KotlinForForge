import net.minecraftforge.gradle.userdev.tasks.JarJar
import org.jetbrains.kotlin.utils.addToStdlib.cast

val kotlin_version: String by project
val annotations_version: String by project
val coroutines_version: String by project
val serialization_version: String by project
val max_kotlin: String by project
val max_coroutines: String by project
val max_serialization: String by project

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("net.minecraftforge.gradle")
    id("com.modrinth.minotaur") version "2.+"
    `maven-publish`
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
kotlin.jvmToolchain {}

// Enable JarInJar
jarJar.enable()

val kotlinSourceJar by tasks.creating(Jar::class) {
    val kotlinSourceSet = kotlin.sourceSets.main.get()

    from(kotlinSourceSet.kotlin.srcDirs)
    archiveClassifier.set("sources")
}

tasks.build.get().dependsOn(kotlinSourceJar)

// Workaround to remove build\java from MOD_CLASSES because SJH doesn't like nonexistent dirs
for (s in arrayOf(sourceSets.main, sourceSets.test)) {
    val sourceSet = s.get()
    val mutClassesDirs = sourceSet.output.classesDirs as ConfigurableFileCollection
    val javaClassDir = sourceSet.java.classesDirectory.get()
    val mutClassesFrom = HashSet(mutClassesDirs.from.filter {
        val provider = it as Provider<*>?
        val toCompare = if (it != null) provider!!.get() else it
        return@filter javaClassDir != toCompare
    })
    mutClassesDirs.setFrom(mutClassesFrom)
}

configurations {
    val library = maybeCreate("library")
    api.configure {
        extendsFrom(library)
    }
}
minecraft.runs.all {
    lazyToken("minecraft_classpath") {
        return@lazyToken configurations["library"].copyRecursive().resolve()
            .joinToString(File.pathSeparator) { it.absolutePath }
    }
}

repositories {
    mavenCentral()
    // For testing with kfflib and making JarJar shut up
    mavenLocal()
}

dependencies {
    minecraft("net.minecraftforge:forge:1.19-41.0.91")

    fun library(dependencyNotation: String, maxVersion: String) {
        add("library", dependencyNotation) {
            exclude("org.jetbrains", "annotations")
            jarJar(group = group!!, name = name, version = "[$version, $maxVersion)") {
                isTransitive = false
                exclude("org.jetbrains", "annotations")
            }
        }
    }
    // Adds to JarJar without using as Gradle dependency
    fun compileLibrary(group: String, name: String, version: String, maxVersion: String) {
        val lib = this.create(group, name, version = "[$version,$maxVersion)")
        jarJar(lib) {
            isTransitive = false
            jarJar.pin(this, version)
        }
    }

    library("org.jetbrains.kotlin:kotlin-reflect:$kotlin_version", max_kotlin)
    library("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version", max_kotlin)
    library("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version", max_coroutines)
    library("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$coroutines_version", max_coroutines)
    library("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization_version", max_serialization)

    // These are necessary to make sure JarJar includes all the correct libraries.
    // The above "library" deps are not transitive in JarJar because JarJar fails to
    // handle them properly, so they are manually added here, one by one.
    compileLibrary("org.jetbrains.kotlin", "kotlin-stdlib-jdk7", kotlin_version, max_kotlin)
    compileLibrary("org.jetbrains.kotlinx", "kotlinx-serialization-core", serialization_version, max_serialization)
    compileLibrary("org.jetbrains.kotlin", "kotlin-stdlib", kotlin_version, max_kotlin)
    compileLibrary("org.jetbrains.kotlin", "kotlin-stdlib-common", kotlin_version, max_kotlin)

    // Include kfflib into JarInJar, but doesn't use it as actual dependency
    compileLibrary("thedarkcolour", "kfflib", "${project.version}", "4.0")

    implementation(group = "org.jetbrains", name = "annotations", version = "[$annotations_version,)") {
        jarJar.pin(this, annotations_version)
    }
}

minecraft.run {
    mappings("official", "1.19")

    runs {
        create("client") {
            workingDirectory(project.file("run"))

            property("forge.logging.markers", "SCAN,LOADING,CORE")
            property("forge.logging.console.level", "debug")

            mods {
                create("kotlinforforge") {
                    source(sourceSets.main.get())
                }

                create("kfflangtest") {
                    source(sourceSets.test.get())
                }
            }
        }

        create("server") {
            workingDirectory(project.file("run/server"))

            property("forge.logging.markers", "SCAN,LOADING,CORE")
            property("forge.logging.console.level", "debug")

            mods {
                create("kotlinforforge") {
                    source(sourceSets.main.get())
                }

                create("kfflangtest") {
                    source(sourceSets.test.get())
                }
            }
        }
    }
}

tasks.withType<Jar> {
    archiveBaseName.set("kotlinforforge")

    manifest {
        attributes(
            mapOf(
                "FMLModType" to "LANGPROVIDER",
                "Specification-Title" to "Kotlin for Forge",
                "Automatic-Module-Name" to "kotlinforforge",
                "Specification-Vendor" to "Forge",
                "Specification-Version" to "1",
                "Implementation-Title" to project.name,
                "Implementation-Version" to "${project.version}",
                "Implementation-Vendor" to "thedarkcolour",
                "Implementation-Timestamp" to `java.text`.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
                    .format(`java.util`.Date())
            )
        )
    }
}

tasks.withType<JarJar> {
    archiveClassifier.set("obf")
}

// Only require the lang provider to use explicit visibility modifiers, not the test mod
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().getByName("compileKotlin") {
    kotlinOptions.freeCompilerArgs = listOf("-Xexplicit-api=warning", "-Xjvm-default=all")
}

fun DependencyHandler.minecraft(
    dependencyNotation: Any
): Dependency? = add("minecraft", dependencyNotation)

fun DependencyHandler.library(
    dependencyNotation: Any
): Dependency? = add("library", dependencyNotation)

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kotlinforforge"
            from(components["kotlin"])
            artifact(kotlinSourceJar)

            // Remove Minecraft from transitive dependencies
            pom.withXml {
                asNode().get("dependencies").cast<groovy.util.NodeList>().first().cast<groovy.util.Node>().children().cast<MutableList<groovy.util.Node>>().removeAll { child ->
                    child.get("groupId").cast<groovy.util.NodeList>().first().cast<groovy.util.Node>().value() == "net.minecraftforge"
                }
            }
        }
    }
}

modrinth {
    projectId.set("ordsPcFz")
    versionNumber.set("${project.version}")
    versionType.set("release")
    uploadFile.set(tasks.jarJar as Any)
    gameVersions.addAll("1.18", "1.18.1", "1.19")
    loaders.add("forge")
}
