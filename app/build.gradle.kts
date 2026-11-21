import java.util.Properties
import javax.inject.Inject

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.baselineprofile)
}

// versionName comes from version.properties, which CI rewrites from the previous release tag and the
// commit types since it; versionCode is the commit count, so a later build always out-ranks an earlier
// one for the package manager. Both fall back to fixed values outside Git so the project configures.
val versionProps = Properties().apply {
	val file = rootProject.file("version.properties")
	if (file.exists()) file.inputStream().use { load(it) }
}

fun git(vararg args: String): String? = runCatching {
	providers.exec {
		commandLine("git", *args)
		workingDir = rootProject.projectDir
	}.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val appVersionName: String = (System.getenv("MARQUEE_VERSION_NAME")
	?: versionProps.getProperty("version")
	?: "0.1.0").trim()

val appVersionCode: Int = (System.getenv("MARQUEE_VERSION_CODE")?.toIntOrNull()
	?: git("rev-list", "--count", "HEAD")?.toIntOrNull()
	?: 1)

val bridgeAbis: List<String> = providers.gradleProperty("marquee.abis").get()
	.split(',').map(String::trim).filter(String::isNotEmpty)

android {
	namespace = "com.nphil.marquee"
	compileSdk = 37
	// Compose 1.13 needs the 37.1 APIs or later.
	compileSdkMinor = 2

	defaultConfig {
		applicationId = "com.nphil.marquee"
		minSdk = 29
		targetSdk = 37
		versionCode = appVersionCode
		versionName = appVersionName

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		ndk { abiFilters += bridgeAbis }
		androidResources { localeFilters += listOf("en") }
	}

	// CI passes the release keystore through these variables. Without them the release build is signed
	// with the local debug key, which will not update an APK installed from a CI release.
	val keystorePath = System.getenv("MARQUEE_KEYSTORE")
	val keystorePassword = System.getenv("MARQUEE_KEYSTORE_PASSWORD")
	signingConfigs {
		if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
			create("release") {
				storeFile = file(keystorePath)
				storePassword = keystorePassword
				keyAlias = System.getenv("MARQUEE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "marquee"
				keyPassword = System.getenv("MARQUEE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword
				enableV1Signing = false
				enableV2Signing = true
				enableV3Signing = true
				enableV4Signing = false
			}
		}
	}

	buildTypes {
		debug {
			applicationIdSuffix = ".debug"
			versionNameSuffix = "-debug"
		}
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
		buildConfig = true
	}

	packaging {
		// The Swift libraries are 75 MB per ABI and zip to 28 MB. Stored compressed, the APK Obtainium
		// downloads on every release is under half the size; the installer unpacks them once.
		jniLibs.useLegacyPackaging = true
		resources.excludes += setOf(
			"/META-INF/{AL2.0,LGPL2.1}",
			"/META-INF/*.kotlin_module",
			"DebugProbesKt.bin",
			"kotlin-tooling-metadata.json",
		)
	}

	testOptions {
		unitTests.all {
			it.systemProperty("marquee.themes", rootProject.file("core/Resources/themes.json").path)
		}
	}

	lint {
		abortOnError = false
		checkReleaseBuilds = false
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
		freeCompilerArgs.addAll(
			"-Xannotation-default-target=param-property",
			"-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
			"-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
			"-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
		)
	}
}

baselineProfile {
	// CI generates the profile on its emulator before the release build; local builds never start one.
	automaticGenerationDuringBuild = false
	dexLayoutOptimization = true
}

/** Builds the Swift bridge for each ABI with bridge/build-android.sh into one jniLibs directory. */
abstract class BuildBridge : DefaultTask() {
	@get:Input
	abstract val abis: ListProperty<String>

	@get:InputFiles
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val sources: ConfigurableFileCollection

	@get:InputFile
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val script: RegularFileProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@get:Inject
	abstract val exec: ExecOperations

	@TaskAction
	fun build() {
		val out = outputDir.get().asFile
		out.listFiles()?.filter { it.name !in abis.get() }?.forEach { it.deleteRecursively() }
		abis.get().forEach { abi ->
			exec.exec { commandLine(script.get().asFile.path, abi, out.path) }
		}
	}
}

/** Copies what the app shares with the iPhone app's resources, so git holds one copy of each. */
abstract class CoreAssets : DefaultTask() {
	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val themes: RegularFileProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val font: RegularFileProperty

	@get:InputFile
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val fontLicence: RegularFileProperty

	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty

	@get:Inject
	abstract val files: FileSystemOperations

	@TaskAction
	fun copy() {
		files.sync {
			into(outputDir)
			from(themes)
			from(font) { into("fonts") }
			from(fontLicence) {
				into("licences")
				rename { "Manrope-OFL.txt" }
			}
		}
	}
}

val buildBridge = tasks.register<BuildBridge>("buildBridge") {
	abis.set(bridgeAbis)
	script.set(rootProject.layout.projectDirectory.file("bridge/build-android.sh"))
	sources.from(
		rootProject.files(
			"bridge/Package.swift",
			"bridge/Package.resolved",
			"bridge/Sources",
			"core/Packages/MarqueeCore/Package.swift",
			"core/Packages/MarqueeCore/Package.resolved",
			"core/Packages/MarqueeCore/Sources",
		)
	)
	outputDir.set(layout.buildDirectory.dir("generated/bridge/jniLibs"))
}

val coreAssets = tasks.register<CoreAssets>("coreAssets") {
	val resources = rootProject.layout.projectDirectory.dir("core/Resources")
	themes.set(resources.file("themes.json"))
	font.set(resources.file("Fonts/Manrope.ttf"))
	fontLicence.set(resources.file("Fonts/OFL.txt"))
	outputDir.set(layout.buildDirectory.dir("generated/core/assets"))
}

androidComponents {
	onVariants { variant ->
		variant.sources.jniLibs?.addGeneratedSourceDirectory(buildBridge, BuildBridge::outputDir)
		variant.sources.assets?.addGeneratedSourceDirectory(coreAssets, CoreAssets::outputDir)
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.splashscreen)
	implementation(libs.androidx.profileinstaller)

	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.foundation)
	implementation(libs.androidx.compose.animation)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material3.navigation.suite)
	implementation(libs.androidx.compose.material3.adaptive)
	implementation(libs.androidx.compose.material3.adaptive.layout)
	implementation(libs.androidx.compose.material3.adaptive.navigation)

	implementation(libs.androidx.datastore.preferences)

	implementation(libs.okhttp)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.coil.compose)
	implementation(libs.coil.network.okhttp)

	baselineProfile(project(":baselineprofile"))

	debugImplementation(libs.androidx.compose.ui.tooling)

	testImplementation(libs.junit)
	testImplementation(libs.okhttp.mockwebserver)

	androidTestImplementation(libs.androidx.test.junit)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.okhttp.mockwebserver)
}
