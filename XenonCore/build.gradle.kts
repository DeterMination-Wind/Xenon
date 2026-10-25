plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.compileJava {
    options.compilerArgs.add("--add-exports=jdk.attach/sun.tools.attach=ALL-UNNAMED")
}

dependencies {
    api(libs.kala.compress.zip)
    api(libs.kala.compress.tar)
    api(libs.gson)
    implementation(libs.hjson)
    api(libs.xz)
    api(libs.fx.gson)
    api(libs.jsoup)
    api(libs.chardet)
    api(libs.jna)
    api(libs.pci.ids)
    api(libs.weburl)

    compileOnlyApi(libs.jetbrains.annotations)

    testImplementation(libs.jna.platform)
    testImplementation(libs.jimfs)
}
