plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "fr.plbls"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IDE used to compile against and launched by ./gradlew runIde
        intellijIdea("2025.3")
    }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2") // the IntelliJ test runtime expects JUnit 4 classes to be present
}

intellijPlatform {
    // no IDE needed at build time for these two
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"          // 2025.2+
            untilBuild = provider { null } // no upper bound
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
