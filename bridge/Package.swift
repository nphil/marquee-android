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
    ],
    targets: [
        .target(name: "CMarqueeJNI"),
        .target(
            name: "MarqueeBridge",
            dependencies: [
                "CMarqueeJNI",
                .product(name: "MarqueeCore", package: "MarqueeCore"),
            ]
        ),
    ]
)
