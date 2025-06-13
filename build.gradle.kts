group = "ca.cleaningdepot.tools"
description = "jpamodelgen-ksp"

plugins {
    kotlin("jvm") version "2.1.21"
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
