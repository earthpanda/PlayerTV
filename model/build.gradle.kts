plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

}

android {
    namespace = "com.ai.playtv.model"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }



dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":support"))
    implementation(libs.retrofit)
    implementation(libs.gson)
    testImplementation(libs.junit)
}
