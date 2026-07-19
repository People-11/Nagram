plugins {
    `kotlin-dsl`
    // kotlin("jvm") version "2.1.0"
}

gradlePlugin {
    plugins {
        register("lottiePreParser") {
            id = "org.telegram.lottie-meta"
            implementationClass = "org.telegram.lottie.LottieMetaPlugin"
        }
    }
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
    incremental = false
}

dependencies {
    implementation(gradleApi())
    implementation("com.android.tools.build:gradle:8.10.1")

    implementation("com.google.code.gson:gson:2.11.0")
}
