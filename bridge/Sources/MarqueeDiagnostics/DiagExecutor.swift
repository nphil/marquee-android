import Dispatch
import Foundation

/// The log's one line of work and the clock it keeps. The journal, the live sender and the report all run on
/// one executor, so none of them needs a lock. The tests drive a manual one and never sleep.
public protocol DiagExecutor: Sendable {
    var now: Date { get }
    /// Runs `work` after whatever is already queued.
    func run(_ work: @escaping @Sendable () -> Void)
    /// Runs `work` once `delay` seconds have passed, unless the timer is cancelled first.
    func run(after delay: TimeInterval, _ work: @escaping @Sendable () -> Void) -> any DiagTimer
}

public protocol DiagTimer: Sendable {
    func cancel()
}

/// The device's executor: a serial, utility-priority queue and the wall clock.
public final class QueueExecutor: DiagExecutor {
    private let queue = DispatchQueue(label: "com.nphil.marquee.diagnostics", qos: .utility)

    public init() {}

    public var now: Date { Date() }

    public func run(_ work: @escaping @Sendable () -> Void) {
        queue.async(execute: work)
    }

    public func run(after delay: TimeInterval, _ work: @escaping @Sendable () -> Void) -> any DiagTimer {
        let item = DispatchWorkItem(block: work)
        queue.asyncAfter(deadline: .now() + max(0, delay), execute: item)
        return QueueTimer(item: item)
    }
}

private struct QueueTimer: DiagTimer, @unchecked Sendable {
    let item: DispatchWorkItem

    func cancel() {
        item.cancel()
    }
}
