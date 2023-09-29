plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.firebaseCrashlytics)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.kotlinAndroid)
    id("kotlin-parcelize")
}

android {
    namespace = "be.mygod.reactmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.mygod.reactmap"
        minSdk = 26
        targetSdk = 34
        versionCode = 60
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        extra["reactmap.defaultDomain"]!!.let { defaultDomain ->
            manifestPlaceholders["defaultDomain"] = defaultDomain
            buildConfigField("String", "DEFAULT_DOMAIN", "\"$defaultDomain\"")
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(platform(libs.firebase.bom))
    implementation(libs.activity)
    implementation(libs.browser)
    implementation(libs.core.ktx)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.fragment.ktx)
    implementation(libs.play.services.location)
    implementation(libs.lifecycle.common)
    implementation(libs.timber)
    implementation(libs.work.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
