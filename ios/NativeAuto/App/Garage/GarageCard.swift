import SwiftUI

/// 11c: the Garage as a pane. Small: one value, big (range by default, with the tank). Expanded / fullscreen: the
/// full set, the choice of that one value, the bike picker, Refuel and Set fuel level. No cost figures on the card.
/// Port of Android `GarageCard`.
struct GarageCard: View {
    let mode: CardMode
    @EnvironmentObject private var garage: GarageStore
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors
    @State private var sheet: GarageSheet?

    var body: some View {
        let state = garage.card
        DashboardCard(pane: .garage, mode: mode, title: state.map { "Garage · \($0.bike.name)" } ?? "Garage") {
            Group {
                if let state {
                    switch mode {
                    case .expanded, .fullscreen: expanded(state)
                    default: small(state)
                    }
                } else {
                    VStack(spacing: 8) {
                        Text("No bike yet").font(.geist(17, .medium)).foregroundStyle(colors.textDim)
                        if mode != .mini { openButton }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .sheet(item: $sheet) { s in
            Group {
                switch s {
                case .refuel(let id):
                    if let bike = garage.bikes.first(where: { $0.id == id }) {
                        RefuelDialog(bike: bike, estimateKm: garage.repo.estimateNow(id), prices: garage.repo.prices()) { garage.logRefuel(id, $0); sheet = nil }
                    }
                case .level(let id):
                    if let bike = garage.bikes.first(where: { $0.id == id }) {
                        FuelLevelDialog(bike: bike, estimateKm: garage.repo.estimateNow(id)) { garage.setFuelLevel(id, bars: $0, ofBars: $1, odometerKm: $2); sheet = nil }
                    }
                case .serviceDone(let item):
                    ServiceDoneDialog(item: item, estimateKm: garage.repo.estimateNow(item.bikeId)) { km, cost, note in
                        garage.markServiceDone(item.id, odometerKm: km, costRp: cost, note: note); sheet = nil
                    }
                default: EmptyView()
                }
            }
            .environment(\.appColors, colors)
            .presentationBackground(colors.surface)
        }
    }

    private var openButton: some View {
        BigButton(title: "Open Garage", icon: "scooter", style: .quiet) { Haptics.tap(); model.showGarage = true }
            .frame(maxWidth: 260).accessibilityIdentifier("garage_card_open")
    }

    // MARK: Small

    private func small(_ s: GarageCardState) -> some View {
        let big = bigValue(s, settings.garageValue)
        let wide = mode == .wide, tiny = mode == .mini || mode == .narrow
        return HStack(spacing: tiny ? 10 : 16) {
            if settings.garageValue == .range {
                TankGauge(fraction: s.fuel.level.fraction, icon: settings.tankIcon)
                    .frame(width: wide ? 44 : tiny ? 32 : 40, height: wide ? 80 : tiny ? 60 : 72)
            }
            valueText(big, size: wide ? 56 : tiny ? 36 : 48)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("garage_card_small")
    }

    // MARK: Expanded

    private func expanded(_ s: GarageCardState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 16) {
                    TankGauge(fraction: s.fuel.level.fraction, icon: settings.tankIcon).frame(width: 48, height: 88)
                    valueText(bigValue(s, .range), size: 48)
                    Spacer()
                    BigButton(title: "Refuel", icon: "fuelpump", style: .main) { Haptics.tap(); sheet = .refuel(s.bike.id) }
                        .frame(maxWidth: 200).accessibilityIdentifier("garage_card_refuel")
                }
                HStack(alignment: .top, spacing: 16) {
                    fact("Since fill-up", s.fuel.level.sinceRefuelKm.map { "\(Int64($0.rounded())) km" } ?? "—")
                    fact("Odometer", s.odometer.estimateKm.map(formatOdometer) ?? "Not set")
                    fact("km/L", String(format: "%.1f", s.fuel.consumption.kmPerL))
                }
                Hint("km/L \(kmplSource(s.fuel.consumption))" + cautiousNote(s.fuel)).accessibilityIdentifier("garage_card_kmpl_source")
                HStack(spacing: 8) {
                    QuietButton(title: "Set fuel level") { sheet = .level(s.bike.id) }.accessibilityIdentifier("garage_card_set_level")
                    if let next = s.nextService {
                        QuietButton(title: "Service done") { sheet = .serviceDone(next.item) }.accessibilityIdentifier("garage_card_service_done")
                    }
                }
                if let next = s.nextService {
                    Text(reminderText(next)).font(.geist(17, .medium)).foregroundStyle(levelColor(next.level) ?? colors.text)
                }
                if garage.bikes.count >= 2 {
                    label("Riding")
                    Segments(options: garage.bikes.map(\.id), selection: Binding(get: { s.bike.id }, set: { Haptics.confirm(); garage.activate($0) })) { id in
                        garage.bikes.first { $0.id == id }?.name ?? ""
                    }
                }
                label("The small card shows")
                // Five choices don't fit one segmented row on a half-width pane: two rows.
                let all = GarageValue.allCases
                ForEach([Array(all.prefix(3)), Array(all.dropFirst(3))], id: \.self) { row in
                    Segments(options: row, selection: Binding(get: { settings.garageValue }, set: { settings.garageValue = $0 })) { $0.label }
                }
                label("Tank icon")
                Segments(options: TankIcon.allCases, selection: $settings.tankIcon) { $0.label }
                openButton
            }
            .padding(.horizontal, 16).padding(.bottom, 12)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("garage_card_full")
    }

    private func label(_ t: String) -> some View { Text(t).font(.geist(14, .medium)).foregroundStyle(colors.textDim) }

    private func fact(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.geist(14, .medium)).foregroundStyle(colors.textDim).lineLimit(1)
            Text(value).font(.geist(22, .semibold)).foregroundStyle(colors.text).lineLimit(1).minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func valueText(_ big: BigValue, size: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(big.text).font(.geist(size, .semibold)).monospacedDigit().foregroundStyle(big.color ?? colors.text)
                .lineLimit(1).minimumScaleFactor(0.5)
                .accessibilityIdentifier("garage_card_value")
            Text(big.label).font(.geist(15)).foregroundStyle(colors.textDim).lineLimit(1)
        }
    }

    private struct BigValue { var text: String; var label: String; var color: Color? }

    private func levelColor(_ l: DueLevel) -> Color? { l == .overdue ? colors.red : l == .soon ? colors.amber : nil }

    private func bigValue(_ s: GarageCardState, _ v: GarageValue) -> BigValue {
        switch v {
        case .range:
            let level = s.fuel.level
            let range = level.cautiousRangeKm
            let low = (level.fraction ?? 1) <= lowFuelFraction
            let label = range == nil ? (level.unknownBecause == .away ? "fill up or set level" : "set fuel level")
                : level.fromGauge ? "range · from gauge" : "range left"
            return BigValue(text: range.map { "\($0) km" } ?? "—", label: label, color: low && range != nil ? colors.red : nil)
        case .sinceRefuel:
            return BigValue(text: s.fuel.level.sinceRefuelKm.map { "\(Int64($0.rounded())) km" } ?? "—", label: "since fill-up")
        case .odometer:
            return BigValue(text: s.odometer.estimateKm.map(formatOdometer) ?? "—", label: "odometer")
        case .kmPerL:
            return BigValue(text: String(format: "%.1f", s.fuel.consumption.kmPerL),
                            label: s.fuel.consumption.learned ? "km/L" : "km/L (starting value)")
        case .nextService:
            guard let next = s.nextService else { return BigValue(text: "—", label: "no service set") }
            return BigValue(text: shortDue(next), label: next.item.name, color: levelColor(next.level))
        }
    }
}

/// "120 km", "−50 km", "12 days", "today".
func shortDue(_ d: ServiceDue) -> String {
    var t = dueText(d)
    if t.hasPrefix("due in ") { t.removeFirst("due in ".count) }
    return t.replacingOccurrences(of: "overdue by ", with: "−").replacingOccurrences(of: "due today", with: "today")
}

/// A fuel-tank icon of stacked bars, like a retro battery meter: lit segments in the text colour, red at the last one;
/// empty slots stay as faint outlines. Unknown fuel: all outlines. PLAIN: a fixed pump icon, red in the last segment.
struct TankGauge: View {
    let fraction: Double?
    var icon: TankIcon = .gauge
    @Environment(\.appColors) private var colors
    @State private var held: Int?

    var body: some View {
        let lit = fraction.map { tankSegments($0, previous: held) } ?? 0
        let fill = fraction != nil && lit <= 1 ? colors.red : colors.text
        let description = fraction == nil ? "Fuel unknown" : "Fuel \(lit) of \(tankSegmentCount)"
        Group {
            if icon == .plain {
                Image(systemName: "fuelpump.fill").resizable().scaledToFit().foregroundStyle(fill)
            } else {
                Canvas { ctx, size in
                    let stroke = size.width * 0.06, capH = size.height * 0.08, capW = size.width * 0.4
                    ctx.fill(Path(roundedRect: CGRect(x: (size.width - capW) / 2, y: 0, width: capW, height: capH), cornerRadius: stroke),
                             with: .color(colors.outline))
                    let top = capH + stroke, bodyH = size.height - top
                    ctx.stroke(Path(roundedRect: CGRect(x: stroke / 2, y: top, width: size.width - stroke, height: bodyH - stroke / 2), cornerRadius: stroke * 2),
                               with: .color(colors.outline), lineWidth: stroke)
                    let pad = stroke * 2, gap = stroke
                    let segH = (bodyH - pad * 2 - gap * CGFloat(tankSegmentCount - 1)) / CGFloat(tankSegmentCount)
                    for i in 0..<tankSegmentCount {
                        // Bottom up: segment 0 is the last to go.
                        let y = top + pad + CGFloat(tankSegmentCount - 1 - i) * (segH + gap)
                        ctx.fill(Path(roundedRect: CGRect(x: pad, y: y, width: size.width - pad * 2, height: segH), cornerRadius: stroke),
                                 with: .color(i < lit ? fill : colors.outline))
                    }
                }
            }
        }
        .onChange(of: lit, initial: true) { _, v in held = fraction == nil ? nil : v }
        .accessibilityElement().accessibilityLabel(description)
        .accessibilityIdentifier("garage_tank")
    }
}

/// The service reminder as a small pill: amber when due soon, red when overdue.
struct ServiceBadge: View {
    let due: ServiceDue
    @Environment(\.appColors) private var colors
    var body: some View {
        let c = due.level == .overdue ? colors.red : colors.amber
        Text(reminderText(due)).font(.geist(14, .medium)).foregroundStyle(c).lineLimit(1)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .overlay(Capsule().strokeBorder(c, lineWidth: 1))
            .accessibilityIdentifier("service_reminder")
    }
}
