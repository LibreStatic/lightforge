pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Lightforge"

include(
    ":app",
    ":benchmark",
    ":baselineprofile",
    ":testdata",
    ":core:remotestorage",
    ":feature:remotebackup",
    ":feature:ownsync",
    ":core:common",
    ":core:model",
    ":core:domain",
    ":core:data",
    ":core:database",
    ":core:mediastore",
    ":core:thumbnail",
    ":core:search",
    ":core:ml",
    ":core:selection",
    ":core:editing-image",
    ":core:raw",
    ":core:editing-video",
    ":core:frame-interpolation",
    ":core:preferences",
    ":core:security",
    ":core:designsystem",
    ":core:navigation",
    ":core:testing",
    ":feature:photos",
    ":feature:collections",
    ":feature:album",
    ":feature:search",
    ":feature:viewer",
    ":feature:details",
    ":feature:photoeditor",
    ":feature:videoeditor",
    ":feature:permissions",
    ":feature:onboarding",
    ":feature:trash",
    ":feature:settings",
   ":feature:privatealbum",
    ":feature:collage",
    ":feature:pdfstudio",
    ":feature:widget",
    ":feature:places",
    ":feature:semanticsearch",
    ":feature:petrecognition",
   ":feature:motionphotos",
    ":feature:objecteraser",
    ":feature:subjectclip",
)

include(":feature:localsharing")
include(":feature:picker")

// Development-only Compose Driver harness for agent UI inspection (see tools/compose-driver/README.md).
// Configured only when one of its tasks is requested, or with -Plightforge.composeDriver for IDE sync,
// so regular builds and test runs never resolve or compile it.
if (
    gradle.startParameter.taskNames.any { it.startsWith(":tools:compose-driver") } ||
    providers.gradleProperty("lightforge.composeDriver").isPresent
) {
    include(":tools:compose-driver")
}
