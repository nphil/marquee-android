import MarqueeCore

#if canImport(Android)
import Android

/// The log's lines in logcat, under the tag `Marquee`, so `adb logcat -s Marquee` shows what the journal
/// holds. liblog is reached through `dlopen`, which keeps the bridge library from linking it.
public enum DiagLogcat {
    private typealias Write = @convention(c) (Int32, UnsafePointer<CChar>?, UnsafePointer<CChar>?) -> Int32

    private static let write: Write? = {
        guard let library = dlopen("liblog.so", RTLD_NOW), let symbol = dlsym(library, "__android_log_write") else { return nil }
        return unsafeBitCast(symbol, to: Write.self)
    }()

    /// Nil where there is no logcat: the line is only journaled.
    public static var sink: DiagLog.Logcat? {
        guard let write else { return nil }
        return { level, line in
            // Android's priorities: DEBUG 3, INFO 4, WARN 5, ERROR 6.
            let priority: Int32 = switch level {
            case .debug: 3
            case .info, .notice: 4
            case .warning: 5
            case .error: 6
            }
            _ = write(priority, "Marquee", line)
        }
    }
}
#else

/// Where there is no logcat, the host the tests run on.
public enum DiagLogcat {
    public static var sink: DiagLog.Logcat? { nil }
}
#endif
