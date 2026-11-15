import SwiftUI

/// 15a placeholder: the speed hero and trip figures on true black, to prove GPS → reducer on the phone.
/// The real dashboard (cards, layouts) is 15b.
struct SpeedScreen: View {
    @StateObject private var trip = LiveTrip()

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let now = Int64(ctx.date.timeIntervalSince1970 * 1e9)
            let s = trip.stats
            VStack(spacing: 12) {
                Text(status(s.gpsStatus)).font(.headline).foregroundStyle(.secondary)
                Text("\(Int((s.currentSpeedMps * 3.6).rounded()))")
                    .font(.system(size: 160, weight: .semibold).monospacedDigit())
                Text("km/h").font(.title2).foregroundStyle(.secondary)
                HStack(spacing: 32) {
                    figure("Max", String(format: "%.0f", s.maxSpeedMps * 3.6))
                    figure("Avg", String(format: "%.0f", s.averageSpeedMps(now: now) * 3.6))
                    figure("km", String(format: "%.2f", s.distanceM / 1000))
                }
                Button("Reset") { trip.reset() }
                    .buttonStyle(.bordered).controlSize(.large).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .foregroundStyle(.white)
        }
        .onAppear { trip.start() }
    }

    private func figure(_ label: String, _ value: String) -> some View {
        VStack {
            Text(value).font(.title.monospacedDigit())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func status(_ g: GpsStatus) -> String {
        if !trip.authorized { return "Location not allowed" }
        switch g {
        case .fix: return "GPS fix"
        case .searching: return "Searching for GPS…"
        case .stale: return "GPS lost"
        case .disabled: return "Location off"
        case .permissionDenied: return "Location not allowed"
        }
    }
}
