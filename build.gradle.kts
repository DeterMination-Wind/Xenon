import determination.xenon.gradle.docs.UpdateDocuments

plugins {
    id("checkstyle")
}

group = "determination"
version = "1.0.0"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.mavenCentralWithMirrors() {
    System.getenv("MAVEN_CENTRAL_REPO").let { repo ->
        if (!repo.isNullOrBlank()) {
            maven(url = repo)
        }
    }
    maven(url = "https://maven.aliyun.com/repository/public")
    maven(url = "https://repo.huaweicloud.com/repository/maven")
    mavenCentral()
}

fun org.gradle.api.artifacts.dsl.RepositoryHandler.jitpackWithMirror() {
    maven(url = "https://maven.aliyun.com/repository/jitpack")
    maven(url = "https://jitpack.io")
}

fun org.gradle.api.artifacts.dsl.RepositoryHandler.minecraftLibrariesWithMirror() {
    maven(url = "https://bmclapi2.bangbang93.com/maven")
    maven(url = "https://libraries.minecraft.net")
}

subprojects {
    apply {
        plugin("java")
        plugin("idea")
        plugin("maven-publish")
        plugin("checkstyle")
    }

    repositories {
        flatDir {
            name = "libs"
            dirs = setOf(rootProject.file("lib"))
        }

        mavenCentralWithMirrors()
        jitpackWithMirror()
        minecraftLibrariesWithMirror()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    @Suppress("UnstableApiUsage")
    tasks.withType<Checkstyle> {
        maxHeapSize.set("2g")

        setConfigProperties("licenseHeaderFile" to rootProject.rootDir.resolve("config/checkstyle/license-header.txt"))
    }

    configure<CheckstyleExtension> {
        sourceSets = setOf()
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging.showStandardStreams = true
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            mavenLocal()
        }
    }

    tasks.register("checkstyle") {
        dependsOn(tasks["checkstyleMain"], tasks["checkstyleTest"])
    }
}

determination.xenon.gradle.javafx.JavaFXUtils.register(rootProject)

defaultTasks("clean", "build")

tasks.register<UpdateDocuments>("updateDocuments") {
    documentsDir.set(layout.projectDirectory.dir("docs"))
}
