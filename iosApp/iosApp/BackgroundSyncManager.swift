import BackgroundTasks
import ComposeApp
import UIKit

/// Schedules and runs opportunistic background auto-sync.
///
/// BGAppRefresh keeps the session alive. A single network-required BGProcessing
/// request runs incrementally and observes the persisted periodic or retry deadline.
///
/// Simulator: tasks won't fire alone — use:
///   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.bettermifitness.sync.refresh"]
///   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.bettermifitness.sync.processing"]
enum BackgroundSyncManager {
    static let taskIdentifier = "com.bettermifitness.sync.refresh"
    static let processingIdentifier = "com.bettermifitness.sync.processing"

    /// Finishing assertion so an in-flight foreground sync gets ~30s grace
    /// when the app backgrounds instead of stopping mid-write.
    private static var finishingTask: UIBackgroundTaskIdentifier = .invalid
    private static var scheduleTicket = UUID()

    /// Registers the task handlers and wires Kotlin → Swift scheduling bridges.
    /// Must be called during app launch.
    static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskIdentifier,
            using: DispatchQueue.main
        ) { task in
            handle(task: task as! BGAppRefreshTask)
        }
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: processingIdentifier,
            using: DispatchQueue.main
        ) { task in
            handleProcessing(task: task as! BGProcessingTask)
        }

        AutoSyncBridge.shared.setHandlers(
            onSchedule: { DispatchQueue.main.async { schedule() } },
            onScheduleRefresh: { DispatchQueue.main.async { scheduleRefresh() } },
            onCancel: { DispatchQueue.main.async { cancel() } },
            statusProvider: { backgroundRefreshStatusLabel() },
            supportsDebugTest: { KotlinBoolean(bool: isDebugRefreshEnabled) }
        )
    }

    /// Debug / Simulator only — never shown or callable in App Store Release.
    static var isDebugRefreshEnabled: Bool {
        #if DEBUG
        return true
        #elseif targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    static func schedule(minimumDelay: TimeInterval = 0, completion: (() -> Void)? = nil) {
        let ticket = UUID()
        scheduleTicket = ticket
        BackgroundSync.shared.automaticScheduleDelay { delay in
            defer { completion?() }
            guard scheduleTicket == ticket else { return }
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: processingIdentifier)
            guard delay.doubleValue >= 0 else { return }
            let processing = BGProcessingTaskRequest(identifier: processingIdentifier)
            processing.requiresNetworkConnectivity = true
            processing.requiresExternalPower = false
            processing.earliestBeginDate = Date(timeIntervalSinceNow: max(minimumDelay, delay.doubleValue))
            do {
                try BGTaskScheduler.shared.submit(processing)
            } catch {
                print("[BGSync] schedule processing failed: \(error)")
            }
        }
    }

    /// Cancels pending requests (e.g. auto-sync turned off).
    static func cancel() {
        scheduleTicket = UUID()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: processingIdentifier)
    }

    /// Keeps a lightweight keepalive tick scheduled even when data sync is off.
    /// The handler rolls the passport session forward (no data sync), which is what
    /// keeps the login alive during weeks without a sync.
    static func scheduleRefresh() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
        let refresh = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 12 * 60 * 60)
        do {
            try BGTaskScheduler.shared.submit(refresh)
            print("[BGSync] scheduled keepalive \(taskIdentifier)")
        } catch {
            print("[BGSync] keepalive schedule failed: \(error)")
        }
    }

    /// Debug/simulator-only diagnostic. The OS permission does not indicate this app's Auto-sync state.
    static func backgroundRefreshStatusLabel() -> String {
        guard isDebugRefreshEnabled else { return "" }

        #if targetEnvironment(simulator)
        return L10n.backgroundSimulator
        #else
        switch UIApplication.shared.backgroundRefreshStatus {
        case .available:
            return L10n.backgroundOn
        case .denied:
            return L10n.backgroundOff
        case .restricted:
            return L10n.backgroundRestricted
        @unknown default:
            return L10n.backgroundUnknown
        }
        #endif
    }

    /// Runs the same 1-day path as BGAppRefresh (Debug / Simulator only).
    /// URL: `bettermifitness://debug/bg-refresh`
    static func handleDebugURL(_ url: URL) {
        guard isDebugRefreshEnabled else { return }
        guard url.scheme == "bettermifitness" else { return }
        let host = url.host?.lowercased() ?? ""
        let path = url.path.lowercased()
        guard host == "debug", path.contains("bg-refresh") else { return }
        print("[BGSync] debug URL trigger — running opportunistic 1-day sync")
        BackgroundSync.shared.runOpportunisticBackgroundSync { status in
            let code = (status as? String) ?? "?"
            print("[BGSync] debug opportunistic finished status=\(code)")
        }
    }

    /// Begins the finishing assertion; call from scenePhase.background so an
    /// in-flight sync can complete instead of stopping mid-write.
    /// Wired in iOSApp; kept here so all BGTask-adjacent code stays together.
    static func beginFinishing() {
        guard finishingTask == .invalid else { return }
        finishingTask = UIApplication.shared.beginBackgroundTask(withName: "finish-sync") {
            endFinishing()
        }
    }

    /// Ends the finishing assertion; call when the app returns to foreground.
    static func endFinishing() {
        guard finishingTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(finishingTask)
        finishingTask = .invalid
    }

    private static func handle(task: BGAppRefreshTask) {
        scheduleRefresh()

        var finished = false
        func finish(success: Bool) {
            guard !finished else { return }
            finished = true
            task.setTaskCompleted(success: success)
        }

        task.expirationHandler = {
            DispatchQueue.main.async { finish(success: false) }
        }

        BackgroundSync.shared.runSessionRefresh { status in
            finish(success: (status as? String) == BackgroundSync.shared.STATUS_SUCCESS)
        }
    }

    private static func handleProcessing(task: BGProcessingTask) {
        schedule(minimumDelay: 15 * 60)

        var finished = false
        func finish(success: Bool) {
            guard !finished else { return }
            finished = true
            task.setTaskCompleted(success: success)
        }

        task.expirationHandler = {
            DispatchQueue.main.async {
                guard !finished else { return }
                BackgroundSync.shared.cancel()
                finish(success: false)
            }
        }

        BackgroundSync.shared.runOpportunisticBackgroundSync { status in
            guard !finished else { return }
            let code = (status as? String) ?? BackgroundSync.shared.STATUS_FAILED
            let ok = code == BackgroundSync.shared.STATUS_SUCCESS
                || code == BackgroundSync.shared.STATUS_COMPLETED_WITH_SKIPS
                || code == BackgroundSync.shared.STATUS_SKIPPED
            print("[BGSync] processing sync finished status=\(code)")
            schedule(minimumDelay: 5 * 60) { finish(success: ok) }
        }
    }
}
