// swift-tools-version: 6.2
import PackageDescription

// The Android side of the shared Swift core: one shared library the app loads with
// System.loadLibrary("MarqueeBridge").
//
// Raw JNI rather than swift-java. The surface is one coarse JSON call, its cancel and an HTTP
// upcall, so a C file of JNI glue is the whole binding. swift-java would add a JDK 24+ build
// step, generated sources and its own runtime libraries to the APK for no gain at this size.
let package = Package(
    name: "MarqueeBridge",
    products: [
        .library(name: "MarqueeBridge", type: .dynamic, targets: ["MarqueeBridge"]),
    ],
    dependencies: [
        .package(path: "../core/Packages/MarqueeCore"),
        .package(url: "https://github.com/nphil/LumaRemoteKit", exact: "1.0.3"),
    ],
    targets: [
        .target(name: "CMarqueeJNI"),
        // The diagnostic log's pipeline (journal, live sender, reports). No JNI, so its tests run on the host.
        .target(
            name: "MarqueeDiagnostics",
            dependencies: [
                .product(name: "MarqueeCore", package: "MarqueeCore"),
            ]
        ),
        .target(
            name: "MarqueeBridge",
            dependencies: [
                "CMarqueeJNI",
                "MarqueeDiagnostics",
                .product(name: "MarqueeCore", package: "MarqueeCore"),
                .product(name: "LumaRemoteCore", package: "LumaRemoteKit"),
            ]
        ),
        .testTarget(
            name: "MarqueeDiagnosticsTests",
            dependencies: [
                "MarqueeDiagnostics",
                .product(name: "MarqueeCore", package: "MarqueeCore"),
            ]
        ),
        // The browse calls against a scripted server. No JNI is called, but the target links the bridge, so the
        // host build needs the JDK headers, as the other tests do.
        .testTarget(
            name: "MarqueeBridgeTests",
            dependencies: [
                "CMarqueeJNI",
                "MarqueeBridge",
                .product(name: "MarqueeCore", package: "MarqueeCore"),
                .product(name: "LumaRemoteCore", package: "LumaRemoteKit"),
                .product(name: "LumaRemoteFakeTV", package: "LumaRemoteKit"),
            ]
        ),
    ]
)
