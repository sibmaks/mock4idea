import java.text.SimpleDateFormat
import java.util.*

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

val versionFromProperty = "${project.property("version")}"
val versionFromEnv : String? = System.getenv("VERSION")

version = versionFromEnv ?: versionFromProperty
group = "${project.property("group")}"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.2")
        bundledPlugin("com.intellij.java")
    }

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "${group}.${project.property("project_name")}"
        name = "Mockito Tweaks 4 IDEA"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252"
        }

        description = """
            <p>Mockito Tweaks 4 IDEA speeds up routine Mockito operations in Java tests.</p>
            <p>It provides intentions for:</p>
            <ul>
              <li>stubbing selected statements with <code>when(...).thenReturn(...)</code></li>
              <li>wrapping calls with <code>verify(...)</code></li>
              <li>generating Mockito-based <code>&lt;ClassName&gt;Test</code> classes</li>
            </ul>
            <p>The plugin is focused on reducing boilerplate while keeping generated code explicit and readable.</p>
        """.trimIndent()

        changeNotes = """
            Just released
        """.trimIndent()
    }
}


tasks.jar {
    manifest {
        attributes(mapOf(
            "Specification-Title" to project.name,
            "Specification-Vendor" to project.property("author"),
            "Specification-Version" to project.version,
            "Specification-Timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(Date()),
            "Timestamp" to System.currentTimeMillis(),
            "Built-On-Java-Version" to "${System.getProperty("java.vm.version")}",
            "Built-On-Java-Vendor" to "${System.getProperty("java.vm.vendor")}"
        ))
    }
}
