import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

val libraryVersion = "1.3.0-alpha01"

plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.vonage.clientlibrary"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        lint.targetSdk = 35
        version = libraryVersion

        android.buildFeatures.buildConfig = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String","VERSION_NAME","\"$libraryVersion\"")
        }
        release {
            buildConfigField("String","VERSION_NAME","\"$libraryVersion\"")
            isMinifyEnabled=  false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                (this as? org.gradle.api.tasks.testing.Test)?.apply {
                    testLogging {
                        events("passed", "skipped", "failed")
                    }
                }
            }
        }
    }
}

// Configure test compile tasks to use JVM 11 (required for MockK)
afterEvaluate {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name.contains("UnitTest", ignoreCase = true)) {
            kotlinOptions.jvmTarget = "11"
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        if (name.contains("UnitTest", ignoreCase = true)) {
            sourceCompatibility = "11"
            targetCompatibility = "11"
        }
    }
}

mavenPublishing {
    // Publishes to the Sonatype Central Portal (central.sonatype.com).
    // The legacy OSSRH staging API at oss.sonatype.org has been retired
    // and now responds with HTTP 402 to upload requests.
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.vonage", "client-library", libraryVersion)

    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    pom {
        name.set("Vonage Client Library")
        description.set("A library to support using the Vonage APIs on Android")
        url.set("https://github.com/Vonage/vonage-android-client-library")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("vonage")
                name.set("Vonage")
                email.set("devrel@vonage.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/Vonage/vonage-android-client-library.git")
            developerConnection.set("scm:git:ssh://github.com/Vonage/vonage-android-client-library.git")
            url.set("https://github.com/Vonage/vonage-android-client-library")
        }
    }
}

dependencies {
    implementation (libs.androidx.appcompat)
    implementation (libs.kotlin.stdlib)
    implementation (libs.androidx.core.ktx)
    implementation (libs.androidx.credentials)
    implementation (libs.androidx.credentials.play)

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.robolectric:robolectric:4.11.1")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
