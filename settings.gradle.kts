pluginManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "Xenon"
include(
    "Xenon",
    "XenonCore",
    "XenonBoot"
)
