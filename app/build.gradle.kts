import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Razorpay Checkout. Only the PUBLIC key id belongs in the app (it is safe to ship, like a publishable key).
// NEVER put the Razorpay key *secret* in this project or the app: it lives only on your payment server.
// Provide values with -PRAZORPAY_KEY_ID=rzp_test_... or in the git-ignored local.properties. Without a key id, the Pro purchase is switched off.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun prop(name: String) = ((project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: "").trim()
val razorpayKeyId = prop("RAZORPAY_KEY_ID")
require(razorpayKeyId.isEmpty() || (razorpayKeyId.startsWith("rzp_") && razorpayKeyId.length in 12..40)) { "RAZORPAY_KEY_ID looks malformed" }
// Optional payment server (creates orders and verifies payments with the key SECRET, which only the server holds). See docs/PAYMENTS.md.
val paymentApiUrl = prop("PAYMENT_API_URL")
require(paymentApiUrl.isEmpty() || paymentApiUrl.startsWith("https://") || paymentApiUrl.startsWith("http://10.0.2.2")) { "PAYMENT_API_URL must be https://" }
val proPricePaise = prop("PRO_PRICE_PAISE").ifEmpty { "5000" }.toInt()   // ₹50, one time
require(proPricePaise in 100..1_000_000) { "PRO_PRICE_PAISE out of range" }

android {
    namespace = "com.sarath.photovideo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sarath.photovideo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RAZORPAY_KEY_ID", "\"$razorpayKeyId\"")
        buildConfigField("String", "PAYMENT_API_URL", "\"$paymentApiUrl\"")
        buildConfigField("int", "PRO_PRICE_PAISE", proPricePaise.toString())
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val media3 = "1.8.1"
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.android.gms:play-services-ads:25.2.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.razorpay:checkout:1.6.41")
    // checkout 1.6.41 is a thin wrapper whose POM asks for standard-core "LATEST"; pin the real implementation so builds are repeatable.
    implementation("com.razorpay:standard-core") { version { strictly("1.7.20") } }
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
