plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenLocal()
}

description = "Generates endpoint implementations from Smithy Models"

extra["displayName"] = "Smithy :: Rust :: Codegen :: Endpoints"

extra["moduleName"] = "software.amazon.smithy.rust.codegen.endpoints"

group = "software.amazon.smithy.rust.codegen.smithy"

version = "0.1.0"

val smithyVersion: String by project
val kotestVersion: String by project

dependencies {
    implementation(project(":codegen"))
    implementation("software.amazon.smithy:reterminus-core:0.1.0")
    implementation(project(":aws:rust-runtime"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
    testImplementation("software.amazon.smithy:s3-rules:0.1.0")
}


tasks.compileKotlin { kotlinOptions.jvmTarget = "17" }
tasks.compileTestKotlin { kotlinOptions.jvmTarget = "17" }

// Reusable license copySpec
val licenseSpec = copySpec {
    from("${project.rootDir}/LICENSE")
    from("${project.rootDir}/NOTICE")
}

// Configure jars to include license related info
tasks.jar {
    metaInf.with(licenseSpec)
    inputs.property("moduleName", project.name)
    manifest { attributes["Automatic-Module-Name"] = project.name }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}
