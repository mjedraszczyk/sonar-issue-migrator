import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.github.johnrengelman.shadow") version "5.2.0"
    java
    maven
}

repositories {
    jcenter()
    mavenCentral()
}

dependencies {
    implementation("info.picocli:picocli:4.1.4")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.10.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:2.10.1")
    implementation("org.apache.commons:commons-collections4:4.4")
    implementation("org.apache.commons:commons-lang3:3.9")
    implementation("org.apache.httpcomponents:httpclient:4.5.10")
}

tasks {
    named<ShadowJar>("shadowJar") {
        mergeServiceFiles()
        manifest {
            attributes(mapOf("Main-Class" to "org.jmf.client.CommandLineClient"))
        }
    }

    build {
        dependsOn(shadowJar)
    }
}