import determination.xenon.gradle.ci.GitHubActionUtils
import determination.xenon.gradle.ci.JenkinsUtils
import determination.xenon.gradle.l10n.CheckTranslations
import determination.xenon.gradle.l10n.CreateLanguageList
import determination.xenon.gradle.l10n.CreateLocaleNamesResourceBundle
import determination.xenon.gradle.l10n.UpsideDownTranslate
import determination.xenon.gradle.utils.PropertiesUtils
import java.security.MessageDigest

plugins {
    alias(libs.plugins.shadow)
}

val projectConfig = PropertiesUtils.load(rootProject.file("config/project.properties").toPath())

val isOfficial = JenkinsUtils.IS_ON_CI || GitHubActionUtils.IS_ON_OFFICIAL_REPO

val versionType = System.getenv("VERSION_TYPE") ?: if (isOfficial) "nightly" else "unofficial"
val versionRoot = System.getenv("VERSION_ROOT") ?: projectConfig.getProperty("versionRoot") ?: "0"

val buildNumber = System.getenv("BUILD_NUMBER")?.toInt()
if (buildNumber != null) {
    version = if (JenkinsUtils.IS_ON_CI && versionType == "dev") {
        "$versionRoot.0.$buildNumber"
    } else {
        "$versionRoot.$buildNumber"
    }
} else {
    val shortCommit = System.getenv("GITHUB_SHA")?.lowercase()?.substring(0, 7)
    version = if (shortCommit.isNullOrBlank()) {
        "$versionRoot.SNAPSHOT"
    } else if (isOfficial) {
        "$versionRoot.dev-$shortCommit"
    } else {
        "$versionRoot.unofficial-$shortCommit"
    }
}

dependencies {
    implementation(project(":XenonCore"))
    implementation(project(":XenonBoot"))
    implementation("libs:JFoenix")
    implementation(libs.jwebp)
    implementation(libs.fxsvgimage)
    implementation(libs.java.info)
    implementation(libs.monet.fx)
    implementation(libs.nayuki.qrcodegen)
}

fun digest(algorithm: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(bytes)

fun createChecksum(file: File) {
    val algorithms = linkedMapOf("SHA-1" to "sha1", "SHA-256" to "sha256", "SHA-512" to "sha512")
    algorithms.forEach { (algorithm, ext) ->
        File(file.parentFile, "${file.name}.$ext").writeText(
            digest(algorithm, file.readBytes()).joinToString(separator = "", postfix = "\n") { "%02x".format(it) }
        )
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.checkstyleMain {
    exclude("**/determination/xenon/ui/image/apng/**")
}

val addOpens = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "javafx.base/com.sun.javafx.binding",
    "javafx.base/com.sun.javafx.event",
    "javafx.base/com.sun.javafx.runtime",
    "javafx.base/javafx.beans.property",
    "javafx.graphics/javafx.css",
    "javafx.graphics/javafx.stage",
    "javafx.graphics/javafx.scene",
    "javafx.graphics/com.sun.glass.ui",
    "javafx.graphics/com.sun.javafx.stage",
    "javafx.graphics/com.sun.javafx.util",
    "javafx.graphics/com.sun.prism",
    "javafx.controls/com.sun.javafx.scene.control",
    "javafx.controls/com.sun.javafx.scene.control.behavior",
    "javafx.graphics/com.sun.javafx.tk.quantum",
    "javafx.controls/javafx.scene.control.skin",
    "jdk.attach/sun.tools.attach",
)

tasks.compileJava {
    options.compilerArgs.addAll(addOpens.map { "--add-exports=$it=ALL-UNNAMED" })
}

val xenonProperties = buildList {
    add("xenon.version" to project.version.toString())
    add("xenon.add-opens" to addOpens.joinToString(" "))
    System.getenv("GITHUB_SHA")?.let { add("xenon.version.hash" to it) }
    add("xenon.version.type" to versionType)
}

val xenonPropertiesFile = layout.buildDirectory.file("xenon.properties")
val createPropertiesFile by tasks.registering {
    outputs.file(xenonPropertiesFile)
    xenonProperties.forEach { (k, v) -> inputs.property(k, v) }
    doLast {
        val target = xenonPropertiesFile.get().asFile
        target.parentFile.mkdir()
        target.bufferedWriter().use { for ((k, v) in xenonProperties) it.write("$k=$v\n") }
    }
}

tasks.jar {
    enabled = false
    dependsOn(tasks["shadowJar"])
}

val jarPath = tasks.jar.get().archiveFile.get().asFile

tasks.shadowJar {
    dependsOn(createPropertiesFile)

    archiveClassifier.set(null as String?)

    exclude("**/package-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/services/javax.imageio.spi.ImageReaderSpi")
    exclude("META-INF/services/javax.imageio.spi.ImageInputStreamSpi")

    listOf(
        "aix-*", "sunos-*", "openbsd-*", "dragonflybsd-*", "freebsd-*", "linux-*",
        "*-ppc", "*-ppc64le", "*-s390x", "*-armel",
    ).forEach { exclude("com/sun/jna/$it/**") }

    minimize {
        exclude(dependency("com.google.code.gson:.*:.*"))
        exclude(dependency("net.java.dev.jna:jna:.*"))
        exclude(dependency("libs:JFoenix:.*"))
        exclude(project(":XenonBoot"))
    }

    manifest.attributes(
        "Created-By" to "Xenon contributors",
        "Implementation-Version" to project.version.toString(),
        "Main-Class" to "determination.xenon.Main",
        "Multi-Release" to "true",
        "Add-Opens" to addOpens.joinToString(" "),
        "Enable-Native-Access" to "ALL-UNNAMED",
        "Enable-Final-Field-Mutation" to "ALL-UNNAMED",
    )

    doLast {
        createChecksum(jarPath)
    }
}

tasks.processResources {
    dependsOn(createPropertiesFile)
    dependsOn(upsideDownTranslate)
    dependsOn(createLocaleNamesResourceBundle)
    dependsOn(createLanguageList)

    into("assets/") {
        from(xenonPropertiesFile)
    }

    into("assets/lang") {
        from(createLanguageList.map { it.outputFile })
        from(upsideDownTranslate.map { it.outputFile })
        from(createLocaleNamesResourceBundle.map { it.outputDirectory })
    }
}

fun parseToolOptions(options: String?): MutableList<String> {
    if (options == null) return mutableListOf()
    val builder = StringBuilder()
    val result = mutableListOf<String>()
    var offset = 0
    loop@ while (offset < options.length) {
        val ch = options[offset]
        if (Character.isWhitespace(ch)) {
            if (builder.isNotEmpty()) {
                result += builder.toString()
                builder.clear()
            }
            while (offset < options.length && Character.isWhitespace(options[offset])) offset++
            continue@loop
        }
        if (ch == '\'' || ch == '"') {
            offset++
            while (offset < options.length) {
                val ch2 = options[offset++]
                if (ch2 != ch) builder.append(ch2) else continue@loop
            }
            throw GradleException("Unmatched quote in $options")
        }
        builder.append(ch)
        offset++
    }
    if (builder.isNotEmpty()) result += builder.toString()
    return result
}

// IntelliJ IDEA + ./gradlew :Xenon:run
tasks.withType<JavaExec> {
    if (name != "run") jvmArgs(addOpens.map { "--add-opens=$it=ALL-UNNAMED" })
}

tasks.register<JavaExec>("run") {
    dependsOn(tasks.jar)
    group = "application"
    classpath = files(jarPath)
    workingDir = rootProject.rootDir

    val vmOptions = parseToolOptions(System.getenv("XENON_JAVA_OPTS") ?: "-Xmx1g")
    jvmArgs(vmOptions)

    val xenonJavaHome = System.getenv("XENON_JAVA_HOME")
    if (xenonJavaHome != null) {
        this.executable(file(xenonJavaHome).resolve("bin")
            .resolve(if (System.getProperty("os.name").lowercase().startsWith("windows")) "java.exe" else "java"))
    }
    doFirst {
        logger.quiet("XENON_JAVA_OPTS: {}", vmOptions)
        logger.quiet("XENON_JAVA_HOME: {}", xenonJavaHome ?: System.getProperty("java.home"))
    }
}

// =================================================================
//  Translations / l10n
// =================================================================

tasks.register<CheckTranslations>("checkTranslations") {
    val dir = layout.projectDirectory.dir("src/main/resources/assets/lang")
    englishFile.set(dir.file("I18N.properties"))
    simplifiedChineseFile.set(dir.file("I18N_zh_CN.properties"))
    traditionalChineseFile.set(dir.file("I18N_zh.properties"))
    classicalChineseFile.set(dir.file("I18N_lzh.properties"))
}

val generatedDir = layout.buildDirectory.dir("generated")

val upsideDownTranslate by tasks.registering(UpsideDownTranslate::class) {
    inputFile.set(layout.projectDirectory.file("src/main/resources/assets/lang/I18N.properties"))
    outputFile.set(generatedDir.map { it.file("generated/i18n/I18N_en_Qabs.properties") })
}

val createLanguageList by tasks.registering(CreateLanguageList::class) {
    resourceBundleDir.set(layout.projectDirectory.dir("src/main/resources/assets/lang"))
    resourceBundleBaseName.set("I18N")
    additionalLanguages.set(listOf("en-Qabs"))
    outputFile.set(generatedDir.map { it.file("languages.json") })
}

val createLocaleNamesResourceBundle by tasks.registering(CreateLocaleNamesResourceBundle::class) {
    dependsOn(createLanguageList)
    languagesFile.set(createLanguageList.flatMap { it.outputFile })
    outputDirectory.set(generatedDir.map { it.dir("generated/LocaleNames") })
}

// =================================================================
//  W8.4: jpackage cross-platform installers + portable zip
// =================================================================

val jpackageOutDir = layout.buildDirectory.dir("dist")
val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")

val collectJpackageInput by tasks.registering(Copy::class) {
    dependsOn(tasks.shadowJar)
    from(jarPath)
    into(jpackageInputDir)
}

abstract class JPackageTask : Exec() {
    init {
        executable = (System.getenv("JAVA_HOME") ?: System.getProperty("java.home")) +
                "/bin/jpackage" + (if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
        isIgnoreExitValue = true
    }
}

fun commonJPackageArgs(jarName: String, dest: java.io.File): MutableList<String> {
    val args = mutableListOf(
        "--name", "Xenon",
        "--app-version", project.version.toString().substringBefore("-").substringBefore(".SNAPSHOT").ifEmpty { "0.1.0" },
        "--input", jpackageInputDir.get().asFile.absolutePath,
        "--main-jar", jarName,
        "--main-class", "determination.xenon.Main",
        "--dest", dest.absolutePath,
        "--vendor", "Xenon contributors",
        "--copyright", "(c) 2026 Xenon contributors",
        "--description", "Xenon - Mindustry launcher",
        "--java-options", "-Dxenon.installed=true",
    )
    addOpens.forEach { args.addAll(listOf("--java-options", "--add-opens=$it=ALL-UNNAMED")) }
    return args
}

val packageWindows by tasks.registering(JPackageTask::class) {
    group = "distribution"
    description = "Build Xenon-<ver>.msi (Windows host required)"
    dependsOn(collectJpackageInput)
    val dest = jpackageOutDir.get().asFile
    doFirst {
        if (!System.getProperty("os.name").lowercase().contains("win"))
            throw GradleException("packageWindows requires running on Windows")
        dest.mkdirs()
        val args = commonJPackageArgs(jarPath.name, dest)
        args.addAll(listOf("--type", "msi",
            "--win-shortcut", "--win-menu", "--win-menu-group", "Xenon",
            "--win-dir-chooser"))
        commandLine(listOf(executable!!) + args)
    }
}

val packageMac by tasks.registering(JPackageTask::class) {
    group = "distribution"
    description = "Build Xenon-<ver>.dmg (macOS host required)"
    dependsOn(collectJpackageInput)
    val dest = jpackageOutDir.get().asFile
    doFirst {
        if (!System.getProperty("os.name").lowercase().contains("mac"))
            throw GradleException("packageMac requires running on macOS")
        dest.mkdirs()
        val args = commonJPackageArgs(jarPath.name, dest)
        args.addAll(listOf("--type", "dmg",
            "--mac-package-identifier", "com.tinylake.xenon",
            "--java-options", "-XstartOnFirstThread"))
        commandLine(listOf(executable!!) + args)
    }
}

val packageLinuxDeb by tasks.registering(JPackageTask::class) {
    group = "distribution"
    description = "Build xenon_<ver>.deb (Linux host required)"
    dependsOn(collectJpackageInput)
    val dest = jpackageOutDir.get().asFile
    doFirst {
        if (System.getProperty("os.name").lowercase().contains("win") ||
            System.getProperty("os.name").lowercase().contains("mac"))
            throw GradleException("packageLinuxDeb requires running on Linux")
        dest.mkdirs()
        val args = commonJPackageArgs(jarPath.name, dest)
        args.addAll(listOf("--type", "deb", "--linux-shortcut",
            "--linux-menu-group", "Game"))
        commandLine(listOf(executable!!) + args)
    }
}

val packageLinuxAppImage by tasks.registering(JPackageTask::class) {
    group = "distribution"
    description = "Build Xenon app-image (Linux host)"
    dependsOn(collectJpackageInput)
    val dest = jpackageOutDir.get().asFile
    doFirst {
        if (System.getProperty("os.name").lowercase().contains("win") ||
            System.getProperty("os.name").lowercase().contains("mac"))
            throw GradleException("packageLinuxAppImage requires running on Linux")
        dest.mkdirs()
        val args = commonJPackageArgs(jarPath.name, dest)
        args.addAll(listOf("--type", "app-image"))
        commandLine(listOf(executable!!) + args)
    }
}

val genPortableLaunchers by tasks.registering {
    val out = layout.buildDirectory.dir("portable-launchers")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.mkdirs()
        File(dir, "Xenon.bat").writeText(
            "@echo off\r\nstart \"\" javaw -Xmx1g -jar \"%~dp0Xenon.jar\" %*\r\n")
        File(dir, "Xenon.sh").writeText(
            "#!/usr/bin/env bash\nDIR=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"\nexec java -Xmx1g -jar \"\$DIR/Xenon.jar\" \"\$@\"\n")
        File(dir, "README.txt").writeText(
            "Xenon portable build.\n\nWindows: double-click Xenon.bat\nLinux/macOS: chmod +x Xenon.sh && ./Xenon.sh\nNeeds Java 17+ on PATH (or Xenon will offer to download Temurin on first run).\n")
    }
}

val packagePortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Build Xenon-<ver>-portable.zip (no JRE; reuses <config>/java)"
    dependsOn(tasks.shadowJar)
    dependsOn(genPortableLaunchers)
    archiveBaseName.set("Xenon-portable")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(jpackageOutDir)
    from(jarPath) { rename { "Xenon.jar" } }
    from(genPortableLaunchers.map { it.outputs.files })
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Run whichever jpackage target matches the current host + always produce the portable zip"
    dependsOn(packagePortable)
    val osName = System.getProperty("os.name").lowercase()
    when {
        osName.contains("win") -> dependsOn(packageWindows)
        osName.contains("mac") -> dependsOn(packageMac)
        else -> {
            dependsOn(packageLinuxDeb)
            dependsOn(packageLinuxAppImage)
        }
    }
}
