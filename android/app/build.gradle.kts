plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.clipsync.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clipsync.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "protocol.fixtures.dir",
        rootProject.file("../protocol/v1/fixtures").absolutePath,
    )
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("clipsync.e2e.") }
        .forEach { name ->
            val value = System.getProperty(name) ?: return@forEach
            systemProperty(name, value)
        }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

// Static analysis is opt-in (`detekt`, `ktlintCheck`). Do not fail assemble/test.
detekt {
    toolVersion = "1.23.8"
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    autoCorrect = false
    ignoreFailures = false
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    baseline = file("${rootProject.projectDir}/config/detekt/baseline.xml")
    source.setFrom("src/main/java", "src/test/java")
    basePath = rootProject.projectDir.absolutePath
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/build/**")
    exclude("**/generated/**")
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/build/**")
    exclude("**/generated/**")
}

ktlint {
    version.set("1.5.0")
    android.set(true)
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    baseline.set(file("${rootProject.projectDir}/config/ktlint/baseline.xml"))
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// Keep :app:check / assemble / unit tests independent of these opt-in tasks.
tasks.named("check").configure {
    setDependsOn(
        dependsOn.filterNot { dep ->
            val name = when (dep) {
                is TaskProvider<*> -> dep.name
                is Task -> dep.name
                else -> ""
            }
            name.startsWith("detekt") || name.contains("ktlint", ignoreCase = true)
        },
    )
}
