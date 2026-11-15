import Foundation

// Time sources and timers behind small protocols, so the trip logic runs in virtual time in tests
// (Android does the same with `MonotonicClock` and the coroutine test scheduler).

/// Monotonic time in nanoseconds. Never the wall clock for trip maths.
protocol MonotonicClock: AnyObject {
    func nowNanos() -> Int64
}

/// Something running that can be stopped (a timer, a GPS subscription).
protocol Cancellable: AnyObject {
    func cancel()
}

/// Runs a closure later. The app uses the main queue; tests use `VirtualScheduler`.
protocol TaskScheduler: AnyObject {
    @discardableResult
    func schedule(afterMs ms: Int64, _ action: @escaping () -> Void) -> Cancellable
}

/// Uptime clock: the same time base as `ProcessInfo.systemUptime`, immune to wall-clock changes.
final class SystemMonotonicClock: MonotonicClock {
    func nowNanos() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)) }
}

final class MainQueueScheduler: TaskScheduler {
    private final class Item: Cancellable {
        let work: DispatchWorkItem
        init(_ work: DispatchWorkItem) { self.work = work }
        func cancel() { work.cancel() }
    }

    func schedule(afterMs ms: Int64, _ action: @escaping () -> Void) -> Cancellable {
        let work = DispatchWorkItem(block: action)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(ms)), execute: work)
        return Item(work)
    }
}

/// Virtual time for tests: tasks run, in order, only when `advance` passes their due time.
final class VirtualScheduler: TaskScheduler, MonotonicClock {
    private final class Task: Cancellable {
        let due: Int64, seq: Int, action: () -> Void
        var cancelled = false
        init(due: Int64, seq: Int, action: @escaping () -> Void) { self.due = due; self.seq = seq; self.action = action }
        func cancel() { cancelled = true }
    }

    private(set) var nowMs: Int64 = 0
    private var tasks: [Task] = []
    private var seq = 0

    func nowNanos() -> Int64 { nowMs * 1_000_000 }

    func schedule(afterMs ms: Int64, _ action: @escaping () -> Void) -> Cancellable {
        seq += 1
        let t = Task(due: nowMs + max(0, ms), seq: seq, action: action)
        tasks.append(t)
        return t
    }

    /// Moves time forward by `ms`, running every task due on the way (inclusive), each at its own time.
    func advance(by ms: Int64) {
        let end = nowMs + ms
        while true {
            tasks.removeAll { $0.cancelled }
            guard let next = tasks.filter({ $0.due <= end }).min(by: { ($0.due, $0.seq) < ($1.due, $1.seq) }) else { break }
            tasks.removeAll { $0 === next }
            nowMs = next.due
            next.action()
        }
        nowMs = end
    }
}
