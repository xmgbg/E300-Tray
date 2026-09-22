import java.net.InetAddress
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "com.ezhan.amr"
    compileSdk = 35
    val appVersionName = readAppVersionName()

    packagingOptions {
        exclude("META-INF/DEPENDENCIES")
    }

    defaultConfig {
        applicationId = "com.ezhan.amr"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ADD THIS: Generate machine identity
        buildConfigField("String", "DEV_MACHINE_ID", "\"${generateMachineId()}\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"${getCurrentTimestamp()}\"")
    }

    // Correct sourceSets configuration for Kotlin DSL
    sourceSets {
        named("main") {
            assets.srcDirs("src/java/com.ezhan.amr/assets")
            java.srcDirs("src/java")
        }
    }

    buildFeatures {
        buildConfig = true
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "$appVersionName.apk"
        }
    }
}

fun readAppVersionName(): String {
    val versionFile = file("src/main/res/values/version.xml")
    val content = versionFile.readText()
    return Regex("""<string\s+name="app_version"[^>]*>([^<]+)</string>""")
        .find(content)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: error("Missing app_version in ${versionFile.path}")
}

// ADD THESE FUNCTIONS FOR MACHINE ID GENERATION
fun generateMachineId(): String {
    return try {
        val buildDir = File("build")
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }

        val machineIdFile = File(buildDir, "machine_id")

        if (machineIdFile.exists()) {
            // Read existing ID
            machineIdFile.readText().trim()
        } else {
            // Generate new persistent ID
            val newId = "machine_${UUID.randomUUID().toString().replace("-", "").substring(0, 12)}"
            machineIdFile.writeText(newId)
            newId
        }
    } catch (e: Exception) {
        // Fallback that's still relatively stable
        "fb_${System.getProperty("user.home", "unknown").hashCode().toString().replace("-", "x")}"
    }
}

fun getCurrentTimestamp(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
}

dependencies {
    // Keep all your existing dependencies exactly as they are
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.legacy.support.v4)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.datastore.preferences.rxjava3)
    implementation(libs.scenecore)
    implementation(libs.core)
    implementation(libs.lifecycle.process)
    implementation(libs.j2mod)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("androidx.datastore:datastore:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.reactivex.rxjava3:rxjava:3.1.6")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.alibaba:dashscope-sdk-java:2.20.3")
    implementation("org.json:json:20230618")
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    implementation("org.apache.httpcomponents:httpmime:4.5.13")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:2.1.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.5.0")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1") {
        exclude(group = "org.parboiled", module = "parboiled-java")
    }
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1") {
        exclude(group = "org.parboiled", module = "parboiled-java")
    }
    constraints {
        implementation("org.slf4j:slf4j-api:1.7.36")
    }
    implementation("com.google.zxing:core:3.4.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation ("androidx.cardview:cardview:1.0.0")
    implementation ("com.google.android.material:material:1.11.0")
    implementation(fileTree("libs") { include("*.aar") })
    implementation("com.fazecast:jSerialComm:2.10.3")
    implementation ("com.android.volley:volley:1.2.1")
    implementation ("com.github.chrisbanes:PhotoView:2.3.0")
    implementation ("com.android.volley:volley:1.2.1")
    implementation ("org.java-websocket:Java-WebSocket:1.5.3")
    implementation ("com.google.android.material:material:1.6.0")
}
