plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.afshin.emla"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.afshin.emla"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.11.0")
}

val prepareOfflineDictionaryAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("words-new-data.json.gz"))
    from(rootProject.projectDir.parentFile.resolve("dictionary.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild").configure {
    dependsOn(prepareOfflineDictionaryAssets)
}
