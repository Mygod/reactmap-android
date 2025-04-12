import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

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
        applicationId = extra["reactmap.packageName"] as String?
        minSdk = 26
        targetSdk = 36
        versionCode = (extra["reactmap.versionCode"] as String?)?.toInt()
        versionName = extra["reactmap.versionName"] as String?

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (extra.has("reactmap.appName")) resValue("string", "app_name", extra["reactmap.appName"] as String)
        extra["reactmap.defaultDomain"]!!.let { defaultDomain ->
            manifestPlaceholders["defaultDomain"] = defaultDomain
            buildConfigField("String", "DEFAULT_DOMAIN", "\"$defaultDomain\"")
        }
        androidResources.localeFilters += listOf("en-rUS", "pl")
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
    val javaVersion = JavaVersion.VERSION_11
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    kotlinOptions.jvmTarget = javaVersion.toString()
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    lint.informational.add("MissingTranslation")

    sourceSets.getByName("main") {
        java.srcDirs("../brotli/java")
        java.excludes.add("**/brotli/**/*Test.java")
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

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(platform(libs.firebase.bom))
    implementation(libs.activity)
    implementation(libs.browser)
    implementation(libs.core.i18n)
    implementation(libs.core.ktx)
    implementation(libs.firebase.analytics)
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
