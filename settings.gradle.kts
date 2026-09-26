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

rootProject.name = "UGallery"

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
    ":feature:moments",
    ":feature:permissions",
    ":feature:trash",
    ":feature:settings",
    ":feature:profile",
    ":feature:cleanup",
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
