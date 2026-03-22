import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private val javaVersion = JavaVersion.VERSION_11
private val defaultDomain = providers.gradleProperty("reactmap.defaultDomain").orNull
    ?: error("Missing required gradle property reactmap.defaultDomain")
private val supportedDomainsProperty = providers.gradleProperty("reactmap.supportedDomains").orNull
val baseMainManifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
val generatedMainManifest = layout.buildDirectory.file("generated/local-manifest/AndroidManifest.xml")
val generateMainManifest by tasks.registering {
    inputs.file(baseMainManifest)
    inputs.property("supportedDomains", supportedDomainsProperty ?: "")
    outputs.file(generatedMainManifest)

    doLast {
        val supportedDomains = (listOf(defaultDomain) + supportedDomainsProperty
            ?.split(',')
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty))
            .distinct()
        val manifestText = baseMainManifest.asFile.readText()
        val marker = """                <data android:host="${'$'}{defaultDomain}" />"""
        val replacement = supportedDomains.joinToString("\n") { """                <data android:host="$it" />""" }
        check(manifestText.contains(marker)) { "Could not find $marker in ${baseMainManifest.asFile}" }
        generatedMainManifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText(manifestText.replace(marker, replacement))
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.firebaseCrashlytics)
    // https://developers.google.com/android/guides/google-services-plugin#processing_the_json_file
//    alias(libs.plugins.googleServices)
    alias(libs.plugins.kotlinAndroid)
    id("kotlin-parcelize")
}

android {
    namespace = "be.mygod.reactmap"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("reactmap.packageName").orNull
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("reactmap.versionCode").orNull?.toInt()
        versionName = providers.gradleProperty("reactmap.versionName").orNull

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        providers.gradleProperty("reactmap.appName").orNull
            ?.let { resValue("string", "app_name", it) }
        buildConfigField("String", "DEFAULT_DOMAIN", "\"$defaultDomain\"")
        androidResources.localeFilters += listOf("en-rUS", "pl")
        externalNativeBuild.cmake.arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")   // TODO remove for NDK r28
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!pluginManager.hasPlugin("com.google.gms.google-services")) {
                the<CrashlyticsExtension>().mappingFileUploadEnabled = false
            }
        }
    }
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    lint.informational.add("MissingTranslation")

    sourceSets.getByName("main") {
        manifest.srcFile(generatedMainManifest)
        java.directories.add("../brotli/java")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits.abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateMainManifest)
}

tasks.withType<JavaCompile>().configureEach {
    exclude("**/brotli/**/*Test.java")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaVersion.toString())
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(libs.activity)
    implementation(libs.browser)
    implementation(libs.core.i18n)
    implementation(libs.core.ktx)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.ai)
    implementation(libs.firebase.ai.ondevice)
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.fragment.ktx)
    implementation(libs.hiddenapibypass)
    implementation(libs.material)
    implementation(libs.play.services.location)
    implementation(libs.s2.geometry)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.lifecycle.common)
    implementation(libs.timber)
    implementation(libs.work)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
