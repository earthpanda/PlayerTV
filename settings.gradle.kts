pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Use the SDK's local Maven publication; never silently resolve this
        // coordinate from a public repository with the same version.
        exclusiveContent {
            forRepository {
                maven {
                    name = "playbackSdkLocal"
                    url = uri("../PlaybackSDK-playtv/build/repository")
                }
            }
            filter { includeGroup("com.ai.media") }
        }
        // 仓库配置：aliyun；地址与顺序由所选配置生成。
        // 阿里云 HTTP public
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/groups/public/")
            isAllowInsecureProtocol = true
        }
        // 阿里云 jcenter
        maven {
            url = uri("http://maven.aliyun.com/nexus/content/repositories/jcenter")
            isAllowInsecureProtocol = true
        }
        // 阿里云 HTTPS public
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        // JitPack
        maven {
            url = uri("https://jitpack.io")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "PlayTV"
include(":app")
include(":model")
include(":support")
include(":base")
