
import com.android.build.api.variant.BuildConfigField
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private fun String?.splitDomains() = this?.split(',').orEmpty()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

private fun String.javaStringLiteral() = buildString {
    append('"')
    for (char in this@javaStringLiteral) when (char) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(char)
    }
    append('"')
}

private fun Iterable<String>.toBuildConfigStringArray() =
    joinToString(prefix = "new String[] {", postfix = "}") { it.javaStringLiteral() }

abstract class GenerateMainManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseManifest: RegularFileProperty

    @get:Input
    abstract val primaryDomain: Property<String>

    @get:Input
    abstract val additionalDomains: ListProperty<String>

    @get:OutputFile
    abstract val generatedManifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val supportedDomains = (listOf(primaryDomain.get()) + additionalDomains.get())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val manifestFile = baseManifest.get().asFile
        val manifestText = manifestFile.readText()
        val marker = """                <data android:host="${'$'}{defaultDomain}" />"""
        val replacement = supportedDomains.joinToString("\n") { """                <data android:host="$it" />""" }
        check(manifestText.contains(marker)) { "Could not find $marker in $manifestFile" }
        generatedManifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText(manifestText.replace(marker, replacement))
        }
    }
}

abstract class GenerateBuildTypeSupportedDomainsManifestTask : DefaultTask() {
    @get:Input
    abstract val additionalDomains: ListProperty<String>

    @get:OutputFile
    abstract val generatedManifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val supportedDomains = additionalDomains.get()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val domains = supportedDomains.joinToString("\n") { """                <data android:host="$it" />""" }
        val manifestText = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        |    <application>
        |        <activity android:name=".MainActivity">
        |            <intent-filter android:autoVerify="true">
        |                <action android:name="android.intent.action.VIEW" />
        |
        |                <category android:name="android.intent.category.DEFAULT" />
        |                <category android:name="android.intent.category.BROWSABLE" />
        |
        |                <data android:scheme="http" />
        |                <data android:scheme="https" />
        |$domains
        |            </intent-filter>
        |        </activity>
        |    </application>
        |</manifest>
        |
        """.trimMargin()
        generatedManifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText(manifestText)
        }
    }
}

private val javaVersion = JavaVersion.VERSION_11
private val defaultDomain = providers.gradleProperty("reactmap.defaultDomain").orNull
    ?: error("Missing required gradle property reactmap.defaultDomain")
private val supportedDomains = providers.gradleProperty("reactmap.supportedDomains").orNull.splitDomains()
val baseMainManifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
val generatedMainManifest = layout.buildDirectory.file("generated/local-manifest/AndroidManifest.xml")
val generateMainManifest by tasks.registering(GenerateMainManifestTask::class) {
    baseManifest.set(baseMainManifest)
    primaryDomain.set(defaultDomain)
    additionalDomains.set(supportedDomains)
    generatedManifest.set(generatedMainManifest)
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.firebaseCrashlytics)
    // https://developers.google.com/android/guides/google-services-plugin#processing_the_json_file
    alias(libs.plugins.googleServices)
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "be.mygod.reactmap"
    compileSdk = 37

    defaultConfig {
        applicationId = providers.gradleProperty("reactmap.packageName").orNull
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("reactmap.versionCode").orNull?.toInt()
        versionName = providers.gradleProperty("reactmap.versionName").orNull

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        providers.gradleProperty("reactmap.appName").orNull
            ?.let { resValue("string", "app_name", it) }
        buildConfigField("String", "DEFAULT_DOMAIN", defaultDomain.javaStringLiteral())
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
        kotlin.directories.add("../brotli/java")
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

androidComponents {
    onVariants(selector().all()) { variant ->
        val buildType = variant.buildType ?: return@onVariants
        val buildTypeSupportedDomains = providers.gradleProperty("reactmap.$buildType.supportedDomains").orNull.splitDomains()
        val variantSupportedDomains = (listOf(defaultDomain) + supportedDomains + buildTypeSupportedDomains).distinct()
        checkNotNull(variant.buildConfigFields).put(
            "SUPPORTED_DOMAINS",
            BuildConfigField("String[]", variantSupportedDomains.toBuildConfigStringArray(), "Supported ReactMap domains."),
        )
        if (buildTypeSupportedDomains.isNotEmpty()) {
            val generateManifest = tasks.register<GenerateBuildTypeSupportedDomainsManifestTask>(
                variant.computeTaskName("generate", "supportedDomainsManifest")
            ) {
                additionalDomains.set(buildTypeSupportedDomains)
            }
            variant.sources.manifests.addGeneratedManifestFile(generateManifest) { it.generatedManifest }
        }
    }
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
    implementation(libs.appcompat)
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
    implementation(libs.mlkit.genai.prompt)
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
