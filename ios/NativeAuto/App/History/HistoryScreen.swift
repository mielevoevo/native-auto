import SwiftUI
import UniformTypeIdentifiers

/// The trip history, drawn over the dashboard (the live map stays put underneath and sleeps). Day groups with their
/// totals, a card per trip, the trip's detail with GPX export and a two-tap delete; Back up / Import. Port of Android
/// `TripHistoryScreen`.
struct HistoryScreen: View {
    @EnvironmentObject private var history: HistoryStore
    @EnvironmentObject private var garage: GarageStore
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var selected: TripRecord?
    @State private var bikeFilter: String?
    @State private var share: SharedFile?
    @State private var importing = false

    var body: some View {
        VStack(spacing: 0) {
            header
            if let message = history.message {
                Text(message).font(.geist(15)).foregroundStyle(colors.textDim)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).padding(.bottom, 8)
                    .accessibilityIdentifier("history_message")
            }
            if let selected {
                TripDetail(trip: selected, bikeName: bikeName(selected), onExport: { share = history.gpxFile(selected).map(SharedFile.init) },
                           onDelete: { history.delete(selected.id); TripSnapshots.shared.forget(selected.id); self.selected = nil })
            } else {
                list
            }
        }
        .background(colors.bg.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("history_screen")
        .sheet(item: $share) { ShareSheet(url: $0.url).presentationDetents([.medium, .large]) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip]) { result in
            if case .success(let url) = result { history.message = "Importing…"; history.importBackup(url) }
        }
        .onAppear { history.message = nil; history.fillMissingAddresses() }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button {
                Haptics.tap()
                if selected != nil { withAnimation(.easeOut(duration: 0.2)) { selected = nil } } else { dismiss() }
            } label: {
                Image(systemName: "chevron.backward").font(.system(size: 20, weight: .semibold)).foregroundStyle(colors.text)
                    .frame(width: 56, height: 56).contentShape(Rectangle())
            }
            .buttonStyle(.plain).accessibilityLabel("Back").accessibilityIdentifier("history_back")
            Text(selected.map { formatDayLong($0.startedAtMs) } ?? "Trip history")
                .font(.geist(22, .semibold)).foregroundStyle(colors.text).lineLimit(1)
            Spacer()
            if selected == nil {
                QuietButton(title: "Import") { importing = true }.accessibilityIdentifier("history_import")
                QuietButton(title: "Back up") {
                    history.message = "Preparing backup…"
                    share = history.backupFile().map(SharedFile.init)
                    if share != nil { history.message = nil }
                }
                .accessibilityIdentifier("history_backup")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 4)
    }

    private var bikeChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Chip(title: "All bikes", on: bikeFilter == nil) { bikeFilter = nil }.accessibilityIdentifier("history_bike_all")
                ForEach(garage.bikes, id: \.id) { b in
                    Chip(title: b.name, on: bikeFilter == b.id) { bikeFilter = b.id }.accessibilityIdentifier("history_bike_\(b.name)")
                }
            }
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder private var list: some View {
        let trips = history.trips.filter { bikeFilter == nil || $0.bikeId == bikeFilter }
        if history.trips.isEmpty {
            VStack(spacing: 8) {
                Spacer()
                Text("No trips yet").font(.geist(22, .semibold)).foregroundStyle(colors.text)
                Text("A trip is saved when you end it (trips under 300 m or 2 min of riding are skipped).")
                    .font(.geist(15)).foregroundStyle(colors.textDim).multilineTextAlignment(.center)
                Spacer()
            }
            .padding(24).frame(maxWidth: .infinity)
        } else {
            if garage.bikes.count >= 2 { bikeChips.padding(.bottom, 8) }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 420), spacing: 12)], spacing: 12, pinnedViews: []) {
                    ForEach(groupByDay(trips, nowMs: SystemWallClock().nowMs()), id: \.day) { group in
                        Section {
                            ForEach(group.trips, id: \.id) { t in
                                TripCard(trip: t, bikeName: bikeName(t)) {
                                    Haptics.tap(); withAnimation(.easeOut(duration: 0.2)) { selected = t }
                                }
                            }
                        } header: {
                            HStack {
                                Text("\(group.label) · \(formatDistance(group.totalDistanceM, settings.speedUnit))")
                                    .font(.geist(15, .semibold)).foregroundStyle(colors.textDim)
                                Spacer()
                            }
                            .padding(.top, 8)
                            .accessibilityIdentifier("history_day_\(group.day.year)-\(group.day.month)-\(group.day.day)")
                        }
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 24)
            }
            .accessibilityElement(children: .contain)
        .accessibilityIdentifier("history_list")
        }
    }

    /// The bike's name on a trip, only once there are two bikes to tell apart.
    private func bikeName(_ t: TripRecord) -> String? {
        guard garage.bikes.count >= 2, let id = t.bikeId else { return nil }
        return garage.bikes.first { $0.id == id }?.name
    }
}

private struct TripCard: View {
    let trip: TripRecord
    let bikeName: String?
    let onTap: () -> Void
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                TripMapImage(trip: trip, size: CGSize(width: 300, height: 236))
                    .frame(width: 150, height: 118).clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(formatClock(trip.startedAtMs))–\(formatClock(trip.endedAtMs))" + (bikeName.map { " · \($0)" } ?? ""))
                        .font(.geist(14, .medium)).foregroundStyle(colors.textDim)
                    Endpoints(trip: trip, lines: 1)
                    HStack(spacing: 14) {
                        mini(formatDistance(trip.distanceM, settings.speedUnit), "distance")
                        mini(formatDuration(trip.durationMs), "time")
                        mini(formatSpeed(trip.averageSpeedMps, settings.speedUnit), "avg")
                        mini(formatSpeed(trip.maxSpeedMps, settings.speedUnit), "max")
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(colors.surface, in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(colors.outline, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("history_trip")
    }

    private func mini(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value).font(.geist(15, .semibold)).foregroundStyle(colors.text).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.geist(12)).foregroundStyle(colors.textDim)
        }
    }
}

/// "Street, Area → Street, Area" (start plain, end dot in the accent).
private struct Endpoints: View {
    let trip: TripRecord
    let lines: Int
    var large = false
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            line(trip.startAddress ?? "Start", colors.text)
            line(trip.endAddress ?? "End", colors.accent)
        }
    }

    private func line(_ text: String, _ dot: Color) -> some View {
        HStack(spacing: 8) {
            Circle().fill(dot).frame(width: 8, height: 8)
            Text(text).font(.geist(large ? 18 : 15)).foregroundStyle(colors.text).lineLimit(lines)
        }
    }
}

private struct TripDetail: View {
    let trip: TripRecord
    let bikeName: String?
    let onExport: () -> Void
    let onDelete: () -> Void
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors
    @State private var deleteArmed = false
    @State private var disarm: Task<Void, Never>?

    var body: some View {
        GeometryReader { g in
            let portrait = g.size.height > g.size.width
            Group {
                if portrait {
                    VStack(spacing: 12) {
                        map.frame(height: min(260, g.size.height * 0.38))
                        ScrollView { info }
                        HStack(spacing: 12) { buttons }
                    }
                } else {
                    HStack(spacing: 20) {
                        map.frame(width: g.size.width * 0.56)
                        VStack(spacing: 12) {
                            ScrollView { info }
                            HStack(spacing: 12) { buttons }
                        }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 8)
        }
    }

    private var map: some View {
        TripMapImage(trip: trip).clipShape(RoundedRectangle(cornerRadius: 20)).accessibilityIdentifier("history_map")
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("\(formatClock(trip.startedAtMs)) – \(formatClock(trip.endedAtMs))" + (bikeName.map { " · \($0)" } ?? ""))
                .font(.geist(17, .medium)).foregroundStyle(colors.textDim)
            Endpoints(trip: trip, lines: 2, large: true)
            let stats = TelemetryStat.allCases.filter { !settings.hiddenStats.contains($0) }
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], alignment: .leading, spacing: 12) {
                ForEach(stats, id: \.self) { s in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.label).font(.geist(14, .medium)).foregroundStyle(colors.textDim)
                        Text(value(s)).font(.geist(24, .semibold)).foregroundStyle(colors.text)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var buttons: some View {
        BigButton(title: "Export GPX", icon: "square.and.arrow.up", style: .quiet, action: onExport)
            .accessibilityIdentifier("history_export")
        BigButton(title: deleteArmed ? "Tap again to delete" : "Delete trip", icon: "trash", style: deleteArmed ? .danger : .quiet) {
            if deleteArmed {
                Haptics.confirm(); disarm?.cancel(); onDelete()
            } else {
                Haptics.tap(); deleteArmed = true
                disarm = Task { try? await Task.sleep(nanoseconds: 3_000_000_000); if !Task.isCancelled { deleteArmed = false } }
            }
        }
        .accessibilityIdentifier("history_delete")
    }

    private func value(_ s: TelemetryStat) -> String {
        let u = settings.speedUnit
        switch s {
        case .distance: return formatDistance(trip.distanceM, u)
        case .time: return formatDuration(trip.durationMs)
        case .average: return formatSpeed(trip.averageSpeedMps, u)
        case .max: return formatSpeed(trip.maxSpeedMps, u)
        case .movingAvg: return formatSpeed(trip.movingAverageMps, u)
        case .movingTime: return formatDuration(trip.movingMs)
        case .stoppedTime: return formatDuration(trip.stoppedMs)
        }
    }
}

// MARK: - Small shared pieces (history and Garage)

/// "14:05" in the phone's zone.
func formatClock(_ ms: Int64) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "HH:mm"
    return f.string(from: dateOf(ms))
}

/// "Mon 17 Nov".
func formatDayLong(_ ms: Int64) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "EEE d MMM"
    return f.string(from: dateOf(ms))
}

/// A small text button for screen headers (56 pt tall for gloves).
struct QuietButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        Button { Haptics.tap(); action() } label: {
            Text(title).font(.geist(16, .semibold)).foregroundStyle(colors.onButtonQuiet)
                .padding(.horizontal, 18).frame(minHeight: 48)
                .background(colors.buttonQuiet, in: Capsule())
                .frame(minHeight: 56).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A selectable chip (bikes, choices): navy when on.
struct Chip: View {
    let title: String
    let on: Bool
    let action: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        Button { if !on { Haptics.tick() }; action() } label: {
            Text(title).font(.geist(15, .medium)).lineLimit(1)
                .foregroundStyle(on ? colors.onNavy : colors.text)
                .padding(.horizontal, 16).frame(minHeight: 48)
                .background(on ? colors.navy : colors.buttonQuiet, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

struct SharedFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// The system share sheet for one file (AirDrop, Files, Mail…).
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
