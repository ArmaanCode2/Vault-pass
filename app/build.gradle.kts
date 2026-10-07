import com.android.apksig.ApkVerifier
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.kotlin.serialization)
}

// Release key from the environment; Android Studio's injected signing properties are handled by AGP itself.
val envKeystorePath: String? = providers.environmentVariable("KEYSTORE_PATH").orNull
val envStorePassword: String? = providers.environmentVariable("STORE_PASSWORD").orNull
val envKeyPassword: String? = providers.environmentVariable("KEY_PASSWORD").orNull

// Update feed. Release always uses GitHub (also locked in UpdateConfig); -PupdateFeedUrl=... only reaches the debug
// (blank without it: no updater) and updateTest (GitHub without it) build types.
val githubUpdateFeedUrl = "https://api.github.com/repos/ArmaanCode2/Vault-pass/releases/latest"
val updateFeedOverride: String? = providers.gradleProperty("updateFeedUrl").orNull?.takeIf { it.isNotBlank() }
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Throwaway key for the updateTest build type (-PupdateTestKeystore=... -PupdateTestKeystorePassword=...).
// Without both properties updateTest is left unsigned. Never used for release.
val updateTestKeystore: String? = providers.gradleProperty("updateTestKeystore").orNull
val updateTestKeystorePassword: String? = providers.gradleProperty("updateTestKeystorePassword").orNull
val updateTestKeyAlias: String = providers.gradleProperty("updateTestKeyAlias").orNull ?: "upload"

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.vaultpass.zxqwej"
    minSdk = 24
    targetSdk = 36
    versionCode = 13
    versionName = "2.7.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    manifestPlaceholders["appLabel"] = "@string/app_name"
    buildConfigField("String", "UPDATE_FEED_URL", javaString(githubUpdateFeedUrl))
    buildConfigField("boolean", "UPDATE_ALLOW_LOCALHOST_HTTP", "false")
  }

  signingConfigs {
    // Release signing from the environment (CI / command line). Android Studio's "Generate Signed APK"
    // wizard passes android.injected.signing.* instead, which AGP applies on its own and which wins.
    // Nothing is checked here: a missing key only fails the release packaging path (checkReleaseSigning).
    if (!envKeystorePath.isNullOrEmpty() && !envStorePassword.isNullOrEmpty()) {
      create("release") {
        storeFile = file(envKeystorePath)
        storePassword = envStorePassword
        keyAlias = "upload"
        keyPassword = envKeyPassword.takeUnless { it.isNullOrEmpty() } ?: envStorePassword
      }
    }
    if (!updateTestKeystore.isNullOrEmpty() && !updateTestKeystorePassword.isNullOrEmpty()) {
      create("updateTest") {
        storeFile = file(updateTestKeystore)
        storePassword = updateTestKeystorePassword
        keyAlias = updateTestKeyAlias
        keyPassword = updateTestKeystorePassword
        if (updateTestKeystore.endsWith(".p12", ignoreCase = true)) storeType = "pkcs12"
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // No debug-key fallback: without a release key the APK is left unsigned and checkReleaseSigning
      // stops the packaging step with an explanation.
      signingConfigs.findByName("release")?.let { signingConfig = it }
      // Always the GitHub feed, HTTPS only; the updateFeedUrl property is ignored here.
      buildConfigField("String", "UPDATE_FEED_URL", javaString(githubUpdateFeedUrl))
      buildConfigField("boolean", "UPDATE_ALLOW_LOCALHOST_HTTP", "false")
    }
    debug {
      applicationIdSuffix = ".debug"
      manifestPlaceholders["appLabel"] = "VaultPass Debug"
      // No updater in debug unless a feed is given (-PupdateFeedUrl=...); blank hides the update UI.
      buildConfigField("String", "UPDATE_FEED_URL", javaString(updateFeedOverride ?: ""))
      buildConfigField("boolean", "UPDATE_ALLOW_LOCALHOST_HTTP", "true")
      //signingConfig = signingConfigs.getByName("debugConfig")
    }
    // Release-like build (R8, not debuggable) for testing updates against a local feed (adb reverse).
    // Own app ID, throwaway key, outside the release signing guard and app/release/.
    create("updateTest") {
      initWith(getByName("release"))
      applicationIdSuffix = ".updatetest"
      manifestPlaceholders["appLabel"] = "VaultPass UpdateTest"
      isDebuggable = false
      isMinifyEnabled = true
      signingConfig = signingConfigs.findByName("updateTest")
      matchingFallbacks += listOf("release")
      buildConfigField("String", "UPDATE_FEED_URL", javaString(updateFeedOverride ?: githubUpdateFeedUrl))
      buildConfigField("boolean", "UPDATE_ALLOW_LOCALHOST_HTTP", "true")
    }
  }
  // Cleartext HTTP to localhost only, for the local update feed; release keeps the main (HTTPS-only) config.
  sourceSets {
    getByName("debug").res.srcDir("src/localUpdateFeed/res")
    getByName("updateTest").res.srcDir("src/localUpdateFeed/res")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------------------------
// Release signing guard. Runs only on the release packaging paths (packageRelease / assembleRelease and
// packageReleaseBundle / signReleaseBundle / bundleRelease),
// never at configuration time, so IDE sync, compileReleaseKotlin, R8 and unit tests need no key.
// ---------------------------------------------------------------------------------------------

/** Fails before the release APK or bundle is packaged when no release key was provided. */
abstract class CheckReleaseSigningTask : DefaultTask() {
  @get:Input @get:Optional abstract val injectedStoreFile: Property<String>
  @get:Input abstract val injectedComplete: Property<Boolean>
  @get:Input @get:Optional abstract val envKeystorePath: Property<String>
  @get:Input abstract val envStorePasswordSet: Property<Boolean>

  @TaskAction
  fun check() {
    val injected = injectedStoreFile.orNull
    if (!injected.isNullOrEmpty()) {
      if (!injectedComplete.get()) {
        throw GradleException(
          "Release signing: Android Studio passed a keystore ($injected) but not all of store password, " +
            "key alias and key password. Fill in every field of the Generate Signed APK wizard.",
        )
      }
      if (!File(injected).isFile) throw GradleException("Release signing: keystore not found: $injected")
      return
    }
    val envPath = envKeystorePath.orNull
    if (!envPath.isNullOrEmpty() && envStorePasswordSet.get()) {
      if (!File(envPath).isFile) throw GradleException("Release signing: KEYSTORE_PATH points to a missing file: $envPath")
      return
    }
    throw GradleException(
      """
      |No release signing key was provided, so the release APK or bundle will not be packaged.
      |VaultPass release builds are never signed with the debug key. Either:
      |  - use Build > Generate Signed App Bundle or APK in Android Studio (key alias "upload"), or
      |  - set KEYSTORE_PATH and STORE_PASSWORD (and KEY_PASSWORD if it differs) and run assembleRelease (or bundleRelease) again.
      """.trimMargin(),
    )
  }
}

/**
 * The release signer rules shared by the APK and the bundle checks: never the Android debug certificate,
 * and the certificate SHA-256 must equal vaultpass.releaseCertSha256.
 */
object ReleaseSignerPin {
  fun sha256(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

  fun check(file: File, cert: X509Certificate, pinnedSha256: String?) {
    val subject = cert.subjectX500Principal.name
    val actual = sha256(cert)
    val actualColons = actual.chunked(2).joinToString(":")
    if (subject.contains("CN=Android Debug")) {
      throw GradleException("$file is signed with the Android debug certificate ($subject). Use the release key.")
    }
    val pinned = pinnedSha256?.lowercase()?.filter { it.isLetterOrDigit() }
    if (pinned.isNullOrEmpty()) {
      throw GradleException(
        """
        |vaultpass.releaseCertSha256 is not set, so the release signer cannot be confirmed. Nothing was published.
        |The file is signed by: $subject
        |Its certificate SHA-256 is: $actualColons
        |Before pasting anything, compare it with the "SHA256:" line (keytool prints it in capitals) from
        |  keytool -list -v -keystore <your upload .jks file>
        |Only if the two are identical, add this line to gradle.properties:
        |vaultpass.releaseCertSha256=$actual
        |If they differ, do NOT paste it: this build was signed with a different key than your upload key.
        """.trimMargin(),
      )
    }
    if (pinned != actual) {
      throw GradleException(
        "Release signer mismatch: $file is signed by $subject with SHA-256 $actualColons, " +
          "but vaultpass.releaseCertSha256 is $pinned. Wrong key; nothing was published.",
      )
    }
  }
}

/**
 * Reads the signer of the packaged release APK, rejects the debug certificate and anything that does not
 * match vaultpass.releaseCertSha256, then publishes the APK as release/VaultPass.apk. On any failure the
 * packaged APK(s) it read are deleted as well, so no wrongly signed release APK is left behind.
 */
abstract class VerifyReleaseApkTask : DefaultTask() {
  @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkFolder: DirectoryProperty
  @get:Internal abstract val artifactsLoader: Property<BuiltArtifactsLoader>
  @get:Input @get:Optional abstract val pinnedSha256: Property<String>
  @get:OutputFile abstract val outputApk: RegularFileProperty

  @TaskAction
  fun verify() {
    // A failed check must not leave an older VaultPass.apk behind that looks freshly published.
    val target = outputApk.get().asFile
    target.delete()
    val builtArtifacts = artifactsLoader.get().load(apkFolder.get())
      ?: throw GradleException("No release APK found in ${apkFolder.get().asFile}")
    val packaged = builtArtifacts.elements.map { File(it.outputFile) }
    try {
      val apk = packaged.singleOrNull()
        ?: throw GradleException("Expected exactly one release APK, found ${packaged.size}")
      val result = try {
        ApkVerifier.Builder(apk).build().verify()
      } catch (e: Exception) {
        throw GradleException("Could not read the signature of $apk: ${e.message}", e)
      }
      if (!result.isVerified) {
        throw GradleException("$apk is not validly signed: ${result.allErrors.joinToString("; ")}")
      }
      val cert = result.signerCertificates.singleOrNull()
        ?: throw GradleException("$apk must have exactly one signer, found ${result.signerCertificates.size}")
      ReleaseSignerPin.check(apk, cert, pinnedSha256.orNull)
      apk.copyTo(target, overwrite = true)
      val actualColons = ReleaseSignerPin.sha256(cert).chunked(2).joinToString(":")
      logger.lifecycle("Release APK verified (signer SHA-256 $actualColons) and copied to $target")
    } catch (e: Exception) {
      target.delete()
      packaged.forEach { apk ->
        if (apk.delete()) logger.error("Deleted the rejected release APK $apk")
      }
      throw e
    }
  }
}

/**
 * Checks the signed release bundle (.aab) with the same rules. apksig only verifies APK signatures, so the
 * bundle's JAR signature is read like jarsigner -verify: every entry must be signed by one and the same
 * certificate. On any failure the bundle is deleted.
 */
abstract class VerifyReleaseBundleTask : DefaultTask() {
  @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val bundleFile: RegularFileProperty
  @get:Input @get:Optional abstract val pinnedSha256: Property<String>

  @TaskAction
  fun verify() {
    val bundle = bundleFile.get().asFile
    try {
      val cert = bundleSigner(bundle)
      ReleaseSignerPin.check(bundle, cert, pinnedSha256.orNull)
      val actualColons = ReleaseSignerPin.sha256(cert).chunked(2).joinToString(":")
      logger.lifecycle("Release bundle verified (signer SHA-256 $actualColons): $bundle")
    } catch (e: Exception) {
      if (bundle.delete()) logger.error("Deleted the rejected release bundle $bundle")
      throw e
    }
  }

  private fun bundleSigner(bundle: File): X509Certificate {
    val signers = mutableSetOf<X509Certificate>()
    var signedEntries = 0
    try {
      JarFile(bundle, true).use { jar ->
        val buffer = ByteArray(64 * 1024)
        for (entry in jar.entries()) {
          if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
          // codeSigners is only filled (and the digest checked) once the entry has been read completely.
          jar.getInputStream(entry).use { input -> while (input.read(buffer) >= 0) Unit }
          val entrySigners = entry.codeSigners
          if (entrySigners.isNullOrEmpty()) throw GradleException("$bundle has an unsigned entry: ${entry.name}")
          entrySigners.forEach { signers += it.signerCertPath.certificates.first() as X509Certificate }
          signedEntries++
        }
      }
    } catch (e: SecurityException) {
      throw GradleException("$bundle has an invalid signature: ${e.message}", e)
    }
    if (signedEntries == 0) throw GradleException("$bundle is not signed")
    return signers.singleOrNull()
      ?: throw GradleException("$bundle must have exactly one signer, found ${signers.size}")
  }
}

val checkReleaseSigning = tasks.register<CheckReleaseSigningTask>("checkReleaseSigning") {
  group = "verification"
  description = "Fails the release APK/bundle packaging when no release signing key was provided."
  fun isSet(name: String) = providers.gradleProperty(name).map { it.isNotEmpty() }.orElse(false)
  injectedStoreFile.set(providers.gradleProperty("android.injected.signing.store.file"))
  injectedComplete.set(
    isSet("android.injected.signing.store.password")
      .zip(isSet("android.injected.signing.key.alias")) { a, b -> a && b }
      .zip(isSet("android.injected.signing.key.password")) { a, b -> a && b },
  )
  envKeystorePath.set(providers.environmentVariable("KEYSTORE_PATH"))
  envStorePasswordSet.set(providers.environmentVariable("STORE_PASSWORD").map { it.isNotEmpty() }.orElse(false))
}

androidComponents {
  onVariants(selector().withBuildType("release")) { variant ->
    val verifyApk = tasks.register<VerifyReleaseApkTask>("verify${variant.name.replaceFirstChar { it.uppercase() }}Apk") {
      group = "verification"
      description = "Checks the release APK signer against vaultpass.releaseCertSha256 and copies it to release/VaultPass.apk."
      apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
      artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
      pinnedSha256.set(providers.gradleProperty("vaultpass.releaseCertSha256"))
      outputApk.set(layout.projectDirectory.file("release/VaultPass.apk"))
    }
    val variantName = variant.name.replaceFirstChar { it.uppercase() }
    val verifyBundle = tasks.register<VerifyReleaseBundleTask>("verify${variantName}Bundle") {
      group = "verification"
      description = "Checks the release bundle signer against vaultpass.releaseCertSha256."
      bundleFile.set(variant.artifacts.get(SingleArtifact.BUNDLE))
      pinnedSha256.set(providers.gradleProperty("vaultpass.releaseCertSha256"))
    }
    // No release APK or bundle is packaged or signed without a release key.
    val signingGuarded = setOf("package$variantName", "package${variantName}Bundle", "sign${variantName}Bundle")
    tasks.named { it in signingGuarded }.configureEach { dependsOn(checkReleaseSigning) }
    // The verify tasks delete a rejected APK/bundle, which Gradle's file-system snapshot doesn't see. A missing
    // artifact must never count as up to date, or the next build would skip packaging/signing it.
    val apkDir = variant.artifacts.get(SingleArtifact.APK)
    val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)
    tasks.named { it == "package$variantName" }.configureEach {
      outputs.upToDateWhen { apkDir.get().asFile.listFiles()?.any { f -> f.name.endsWith(".apk") } == true }
    }
    tasks.named { it == "sign${variantName}Bundle" }.configureEach {
      outputs.upToDateWhen { bundle.get().asFile.isFile }
    }
    tasks.named { it == "assemble$variantName" }.configureEach { dependsOn(verifyApk) }
    tasks.named { it == "bundle$variantName" }.configureEach { dependsOn(verifyBundle) }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation("androidx.lifecycle:lifecycle-process:2.8.4")
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.biometric)
  implementation("androidx.fragment:fragment-ktx:1.8.1")
  implementation(libs.security.crypto)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation("com.google.zxing:core:3.5.3")
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.accompanist.permissions)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
