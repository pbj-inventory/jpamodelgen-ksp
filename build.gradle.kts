group = "ca.cleaningdepot.tools"
version = "3.6.3"
description = "jpamodelgen-ksp"

plugins {
    kotlin("jvm") version "2.1.21"
    `maven-publish`
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.21-2.0.2")
    implementation("com.squareup:kotlinpoet:2.0.0")
    implementation("com.squareup:kotlinpoet-ksp:2.0.0")
    implementation("jakarta.persistence:jakarta.persistence-api:3.2.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}
