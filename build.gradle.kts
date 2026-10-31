import net.ltgt.gradle.errorprone.errorprone
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("com.diffplug.spotless") version "8.9.0"
    id("net.ltgt.errorprone") version "5.1.1"
}

group = "io.github.laiwf94"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Security patches newer than the ones Spring Boot 4.1.1 manages. Each is a patch release of
    // the line Boot already uses; osv-scanner in CI fails if one of these falls behind again.
    constraints {
        listOf(
            "tools.jackson.core:jackson-core:3.1.7",
            "tools.jackson.core:jackson-databind:3.1.7",
            "org.apache.tomcat.embed:tomcat-embed-core:11.0.27",
            "org.apache.tomcat.embed:tomcat-embed-el:11.0.27",
            "org.apache.tomcat.embed:tomcat-embed-websocket:11.0.27",
            "org.postgresql:postgresql:42.7.14",
            "at.yawk.lz4:lz4-java:1.11.4",
            "org.xerial.snappy:snappy-java:1.1.10.9",
        ).forEach { implementation(it) }
        // Error Prone's own Guava runs inside javac; patched the same way.
        errorprone("com.google.guava:guava:33.7.2-jre")
    }

    errorprone("com.google.errorprone:error_prone_core:2.50.0")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-micrometer-metrics-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("net.jqwik:jqwik:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        allErrorsAsWarnings = false
    }
}

tasks.test {
    useJUnitPlatform()
    // ProcessCrashRecoveryTest runs the real jar in a child JVM and kills it.
    dependsOn(tasks.bootJar)
    systemProperty("payflow.bootJar", tasks.bootJar.get().archiveFile.get().asFile.absolutePath)
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        googleJavaFormat("1.36.1")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
