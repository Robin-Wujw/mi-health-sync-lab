import Foundation
import ComposeApp
import Combine

@MainActor
final class SyncStore: ObservableObject {
    @Published private(set) var state = Snapshot()
    @Published private(set) var history: [String] = []
    private let vm: SyncViewModel
    private var subscription: FlowSubscription?
    private var historySubscription: FlowSubscription?

    struct MetricRow: Identifiable {
        let id: String
        let label: String
        let kind: String
        let text: String
    }

    struct Snapshot {
        var isSyncing: Bool = false
        var isStopping: Bool = false
        var wasStopped: Bool = false
        var activeDescription: String?
        var healthAvailable: Bool = true
        var availabilityHint: String?
        var permissionError: String?
        var outcomeMessage: String?
        var outcomeIsWarning: Bool = false
        var healthServiceName: String = L10n.healthFallback
        var rangeDays: Int32 = 7
        var readinessChecked: Bool = false
        var metrics: [MetricRow] = []
    }

    init() {
        self.vm = IosAppBridge.shared.createSyncViewModel()
        subscription = FlowSubscription(
            IosAppBridge.shared.watchSync(vm: vm) { [weak self] s in
                Task { @MainActor in
                    self?.apply(s)
                }
            }
        )
        historySubscription = FlowSubscription(
            IosAppBridge.shared.watchSyncHistory(vm: vm) { [weak self] rows in
                Task { @MainActor in self?.history = rows }
            }
        )
    }

    deinit {
        subscription?.cancel()
        historySubscription?.cancel()
    }

    private func apply(_ s: SyncUiState) {
        var snap = Snapshot()
        snap.isSyncing = s.isSyncing
        snap.isStopping = s.isStopping
        snap.wasStopped = s.wasStopped
        snap.activeDescription = IosAppBridge.shared.activeSyncDescription(state: s)
        snap.healthAvailable = s.healthAvailable
        snap.availabilityHint = s.availabilityHint
        snap.permissionError = s.permissionError
        snap.outcomeMessage = s.outcomeMessage
        snap.outcomeIsWarning = s.outcomeIsWarning
        snap.healthServiceName = s.healthServiceName
        snap.rangeDays = s.rangeDays
        snap.readinessChecked = s.readinessChecked

        let keys = IosAppBridge.shared.visibleMetricKeys(state: s) as? [String] ?? []
        let labels = IosAppBridge.shared.visibleMetricLabels(state: s) as? [String] ?? []
        snap.metrics = zip(keys, labels).map { key, label in
            MetricRow(
                id: key,
                label: label,
                kind: IosAppBridge.shared.metricStatusKind(vm: vm, key: key),
                text: IosAppBridge.shared.metricStatusText(vm: vm, key: key)
            )
        }
        state = snap
    }

    func startSync() { vm.startSync() }
    func stopSync() { vm.stopSync() }
    func openHealth() { vm.openHealthService() }
}
