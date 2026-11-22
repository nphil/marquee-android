import java.util.Properties

plugins {
	id("com.android.library")
}

// Media3's FFmpeg extension (Java and JNI, copied unmodified into src/main) over the libraries build.sh makes. The
// file below holds the versions both use.
val versions = Properties().apply { file("versions.properties").inputStream().use { load(it) } }

// The copied Java runs on the app's Media3 artifacts, so the two must be the same release.
check(versions.getProperty("MEDIA3_VERSION") == libs.versions.media3.get()) {
	"ffmpeg/src/main is Media3 ${versions.getProperty("MEDIA3_VERSION")}, but the app uses ${libs.versions.media3.get()}: " +
		"copy the extension's sources from the new release and set MEDIA3_VERSION in ffmpeg/versions.properties."
}

android {
	namespace = "com.nphil.marquee.ffmpeg"
	compileSdk = 37
	compileSdkMinor = 2

	defaultConfig {
		// The native libraries are built against this API level (build.sh), so no device older than it may install them.
		minSdk = versions.getProperty("ANDROID_API").toInt()
		consumerProguardFiles("proguard-rules.txt")
		// The licence screen names these.
		buildConfigField("String", "FFMPEG_VERSION", "\"${versions.getProperty("FFMPEG_VERSION")}\"")
		buildConfigField("String", "MEDIA3_VERSION", "\"${versions.getProperty("MEDIA3_VERSION")}\"")
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		buildConfig = true
	}

	lint {
		abortOnError = false
		checkReleaseBuilds = false
	}
}

dependencies {
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.annotation)
	compileOnly(libs.checker.qual)
}
