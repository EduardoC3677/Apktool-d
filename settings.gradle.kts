// Plugin resolution: explicitly declare repositories so a transient outage of any
// one registry doesn't kill the build. Order matters: Plugin Portal first (canonical
// source for `id "..."` plugin markers), then Maven Central / Google as fallback
// for the underlying plugin JAR coordinates.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "apktool-cli"
include(
    "brut.j.common", "brut.j.util", "brut.j.dir", "brut.j.xml", "brut.j.yaml",
    "brut.apktool:apktool-lib", "brut.apktool:apktool-cli"
)

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {}
    }
}
