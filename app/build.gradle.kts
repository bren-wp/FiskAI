import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "hr.brendigo.fiskai"
    testBuildType = providers.gradleProperty("fiskaiInstrumentedBuildType").orElse("debug").get()
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "hr.brendigo.fiskai"
        minSdk = 24
        targetSdk = 36
        versionCode = 15
        versionName = "0.1.1"
        manifestPlaceholders["appLabel"] = "FiskAI"
        buildConfigField("boolean", "DEMO_MODE", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            manifestPlaceholders["appLabel"] = "FiskAI DEMO"
            buildConfigField("boolean", "DEMO_MODE", "true")
            matchingFallbacks += listOf("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

abstract class RoomSchemaArguments : CommandLineArgumentProvider {
    @get:OutputDirectory
    abstract val schemaDirectory: DirectoryProperty
    override fun asArguments(): Iterable<String> =
        listOf("-Aroom.schemaLocation=" + schemaDirectory.get().asFile.absolutePath)
}

// Separate outputs per variant, no concurrent writes to the same schema file.
tasks.withType<JavaCompile>().configureEach {
    if (name in setOf("compileDebugJavaWithJavac", "compileDemoJavaWithJavac", "compileReleaseJavaWithJavac")) {
        val schemaArgs = project.objects.newInstance(RoomSchemaArguments::class.java)
        schemaArgs.schemaDirectory.set(project.layout.buildDirectory.dir("room-schemas/$name"))
        options.compilerArgumentProviders.add(schemaArgs)
    }
}
