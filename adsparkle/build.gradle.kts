import org.gradle.api.publish.maven.MavenPublication
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
    id("signing")
}

// ─── Coordinates ────────────────────────────────────────────────────────────
val libGroup: String   = project.findProperty("GROUP")          as? String ?: "co.adsparkle"
val libArtifact: String = project.findProperty("POM_ARTIFACT_ID") as? String ?: "adsparkle-android"
val libVersion: String  = project.findProperty("VERSION_NAME")   as? String ?: "0.1.0"

group   = libGroup
version = libVersion

// ─── Android library config ─────────────────────────────────────────────────
android {
    namespace   = "co.adsparkle.sdk"
    compileSdk  = 34

    defaultConfig {
        minSdk     = 21
        targetSdk  = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    // Expose a release variant with sources + javadoc for Maven publishing
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// ─── Dependencies ────────────────────────────────────────────────────────────
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    // Google Play Install Referrer — Android deferred (install) attribution'in
    // cekirdegi: taze kurulumda click_id'yi Play Store referrer'indan kurtarir.
    // (Play Billing'in aksine bu opsiyonel bir ozellik degil, ana attribution
    // yolu oldugu icin reflection yerine gercek bagimlilik olarak eklenir.)
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// ─── Maven publishing ────────────────────────────────────────────────────────
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId    = libGroup
                artifactId = libArtifact
                version    = libVersion

                pom {
                    name.set("AdSparkle Android SDK")
                    description.set("AdSparkle Android SDK — client-side conversion tracking for the AdSparkle affiliate platform.")
                    url.set("https://github.com/Adsparkle/adsparkle-android")
                    inceptionYear.set("2026")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("ViralifAdem")
                            name.set("Viralif / Adem")
                            email.set("mennansafi@gmail.com")
                            organization.set("Viralif")
                            organizationUrl.set("https://viralif.co")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/Adsparkle/adsparkle-android.git")
                        developerConnection.set("scm:git:ssh://github.com/Adsparkle/adsparkle-android.git")
                        url.set("https://github.com/Adsparkle/adsparkle-android/tree/main")
                    }

                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/Adsparkle/adsparkle-android/issues")
                    }
                }
            }
        }

        repositories {
            // ── Maven Central / OSSRH (Sonatype Nexus) ──────────────────────
            // Credentials come from gradle.properties, ~/.gradle/gradle.properties, or env.
            // To publish: ./gradlew publishReleasePublicationToSonatypeRepository
            maven {
                name = "Sonatype"
                val releasesUrl  = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (libVersion.endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = findProperty("OSSRH_USERNAME") as? String
                        ?: System.getenv("OSSRH_USERNAME") ?: ""
                    password = findProperty("OSSRH_PASSWORD") as? String
                        ?: System.getenv("OSSRH_PASSWORD") ?: ""
                }
            }

            // ── Local Maven (for testing without network) ───────────────────
            // ./gradlew publishReleasePublicationToLocalRepository
            maven {
                name = "Local"
                url  = uri(rootProject.layout.buildDirectory.dir("maven-local"))
            }
        }
    }

    // ── GPG signing (required for Maven Central) ─────────────────────────────
    // Signing is skipped automatically when the key properties are absent.
    val signingKeyId   = findProperty("signing.keyId")            as? String ?: System.getenv("SIGNING_KEY_ID")
    val signingKey     = findProperty("signing.key")              as? String ?: System.getenv("SIGNING_KEY")
    val signingPwd     = findProperty("signing.password")         as? String ?: System.getenv("SIGNING_PASSWORD")

    if (!signingKey.isNullOrBlank()) {
        // In-memory (CI-friendly) ASCII-armored key
        signing {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPwd)
            sign(publishing.publications["release"])
        }
    } else {
        val keyRingFile = findProperty("signing.secretKeyRingFile") as? String
        if (!keyRingFile.isNullOrBlank() && !signingKeyId.isNullOrBlank()) {
            signing {
                sign(publishing.publications["release"])
            }
        }
        // If no signing config — configure without signing (JitPack, local builds).
    }
}
