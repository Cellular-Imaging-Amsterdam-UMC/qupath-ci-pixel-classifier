plugins {
    java
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "nl.amsterdamumc"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

javafx {
    version = "25.0.2"
    modules = listOf("javafx.controls", "javafx.swing")
}

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public/")
    maven("https://artifacts.openmicroscopy.org/artifactory/maven/")
}

dependencies {
    listOf("qupath-core", "qupath-core-processing", "qupath-gui-fx", "qupath-extension-processing")
        .forEach {
            compileOnly("io.github.qupath:$it:0.7.0")
            testImplementation("io.github.qupath:$it:0.7.0")
        }

    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("io.github.qupath:qupath-fxtras:0.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    from("LICENSE") { into("META-INF") }
    manifest.attributes(
        "Automatic-Module-Name" to "qupath.ext.cipixel",
        "Implementation-Title" to "CI Pixel Classifier",
        "Implementation-Version" to project.version
    )
}
