repositories {
    System.getenv("MAVEN_CENTRAL_REPO").let { repo ->
        if (!repo.isNullOrBlank()) {
            maven(url = repo)
        }
    }
    maven(url = "https://maven.aliyun.com/repository/public")
    maven(url = "https://repo.huaweicloud.com/repository/maven")
    mavenCentral()
}

dependencies {
    implementation(libs.gson)
    implementation(libs.jna)
    implementation(libs.kala.compress.tar)
    implementation(libs.kala.compress.ar)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    into("determination/xenon/gradle/l10n") {
        from(projectDir.resolve("../XenonCore/src/main/resources/assets/lang/"))
    }
}
