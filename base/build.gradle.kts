import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Track this project-owned file as a Gradle configuration input; no local override.
val designConfig = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("design-config.properties"))
        .asText.get().reader().use { load(it) }
}
val designWidthText = designConfig.getProperty("design.width")
    ?.trim() ?: throw GradleException("design-config.properties must contain design.width (blank is allowed)")
val designWidth = if (designWidthText.isEmpty()) 0 else {
    designWidthText.toIntOrNull()?.takeIf { it in 100..2000 }
        ?: throw GradleException("design.width must be blank or an integer between 100 and 2000")
}

android {
    namespace = "com.ai.playtv.base"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("int", "DESIGN_WIDTH", designWidth.toString())
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.toolkit)
}
