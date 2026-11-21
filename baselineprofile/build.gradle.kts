plugins {
	alias(libs.plugins.android.test)
	alias(libs.plugins.baselineprofile)
}

android {
	namespace = "com.nphil.marquee.baselineprofile"
	compileSdk = 37
	compileSdkMinor = 2

	defaultConfig {
		minSdk = 29
		targetSdk = 37
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	targetProjectPath = ":app"

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}

baselineProfile {
	// CI starts its own emulator; Gradle-managed devices would download a second system image.
	useConnectedDevices = true
}

androidComponents {
	onVariants { variant ->
		val loader = variant.artifacts.getBuiltArtifactsLoader()
		variant.instrumentationRunnerArguments.put(
			"targetAppId",
			variant.testedApks.map { loader.load(it)?.applicationId.orEmpty() },
		)
	}
}

dependencies {
	implementation(libs.androidx.test.junit)
	implementation(libs.androidx.test.uiautomator)
	implementation(libs.androidx.benchmark.macro.junit4)
}
