pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // 工具链自动供给：本地无 JDK 17 时自动下载（CI 已由 setup-java 提供，不触发）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
rootProject.name = "apex-kit-courier"
include(":courier-dispatch")
