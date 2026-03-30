import java.util.Properties

plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "0.0.8"
}

val localProperties = Properties()
localProperties.load(project.rootProject.file("local.properties").inputStream())

android {
    namespace = "com.vonage.clientlibrary"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        lint.targetSdk = 35
        version = "1.1.0-alpha01"

        android.buildFeatures.buildConfig = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String","VERSION_NAME","\"1.1.0-alpha01\"")
        }
        release {
            buildConfigField("String","VERSION_NAME","\"1.1.0-alpha01\"")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

publishing {
    publications {
        create<MavenPublication>("aar") {
            groupId = "com.vonage"
            artifactId = "client-library"
            version = "1.1.0-alpha01"
            val buildDirPath = project.layout.buildDirectory.get().asFile.path
            artifact("$buildDirPath/outputs/aar/${project.name}-release.aar")
            pom {
                name = "Vonage Client Library"
                description =
                    "A library to support using the Vonage APIs on Android"
                url = "https://github.com/Vonage/vonage-android-client-library"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "vonage"
                        name = "Vonage"
                        email = "devrel@vonage.com"
                    }
                }
                scm {
                    connection = "scm:git:git:github.com/Vonage/vonage-android-client-library.git"
                    developerConnection =
                        "scm:git:ssh://github.com/Vonage/vonage-android-client-library.git"
                    url = "https://github.com/Vonage/vonage-android-client-library"
                }

                pom.withXml {
                    val dependenciesNode = asNode().appendNode("dependencies")
                    configurations.getByName("implementation").allDependencies.forEach {
                        val dependencyNode = dependenciesNode.appendNode("dependency")
                        dependencyNode.appendNode("groupId", it.group)
                        dependencyNode.appendNode("artifactId", it.name)
                        dependencyNode.appendNode("version", it.version)

                    }
                }
            }
        }
    }
}

nmcp {
    publishAllPublications {
        username = localProperties["centralUsername"].toString()
        password = localProperties["centralPassword"].toString()
        
        publicationType = "AUTOMATIC"
    }
}

signing {
    val keyId = localProperties["signing.keyId"]?.toString()
    val password = localProperties["signing.password"]?.toString()
    val secretKeyRingFile = localProperties["signing.secretKeyRingFile"]?.toString()
    
    if (keyId != null && password != null && secretKeyRingFile != null) {
        // Set the signing properties for this project
        project.ext["signing.keyId"] = keyId
        project.ext["signing.password"] = password
        project.ext["signing.secretKeyRingFile"] = project.rootProject.file(secretKeyRingFile).absolutePath
        
        sign(publishing.publications["aar"])
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.androidx.credentials)
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
