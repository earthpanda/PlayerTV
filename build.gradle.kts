plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false

}

// Packaging must use the version freshly published by the checked SDK build.
val playbackSdkBuildVersion = providers.gradleProperty("playbackSdkBuildVersion")
val packageRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').matches(Regex("(?i)(assemble|bundle|install|package)(debug|release)?|build"))
}
if (packageRequested && !playbackSdkBuildVersion.isPresent) {
    throw GradleException("Use .\\build-playtv.ps1 to publish the current PlaybackSDK and build PlayTV.")
}
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.ai.media" && requested.name == "playback-android") {
                playbackSdkBuildVersion.orNull?.let { useVersion(it) }
            }
        }
    }
}
