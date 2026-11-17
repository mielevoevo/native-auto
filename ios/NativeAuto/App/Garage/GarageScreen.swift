import SwiftUI

/// Which Garage dialog is open.
enum GarageSheet: Identifiable {
    case editor(BikeRecord?), correct(String), untracked(String), level(String), refuel(String), price(FuelType)
    case service(String, ServiceItem?), serviceDone(ServiceItem)

    var id: String {
        switch self {
        case .editor(let b): "editor-\(b?.id ?? "new")"
        case .correct(let id): "correct-\(id)"
        case .untracked(let id): "untracked-\(id)"
        case .level(let id): "level-\(id)"
        case .refuel(let id): "refuel-\(id)"
        case .price(let f): "price-\(f.rawValue)"
        case .service(_, let s): "service-\(s?.id ?? 0)"
        case .serviceDone(let s): "done-\(s.id)"
        }
    }
}

/// The rider's bikes, the one being ridden, each one's odometer, fuel and services. Drawn over the dashboard like the
/// history (the live map sleeps underneath). Port of Android `GarageScreen` (11a-12c).
struct GarageScreen: View {
    @EnvironmentObject private var garage: GarageStore
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var selectedId: String?
    @State private var sheet: GarageSheet?

    var body: some View {
        let bikes = garage.bikes
        let selected = bikes.first { $0.id == selectedId } ?? bikes.first { $0.active } ?? bikes.first
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button { Haptics.tap(); dismiss() } label: {
                    Image(systemName: "chevron.backward").font(.system(size: 20, weight: .semibold)).foregroundStyle(colors.text)
                        .frame(width: 56, height: 56).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityLabel("Back").accessibilityIdentifier("garage_back")
                Text("Garage").font(.geist(22, .semibold)).foregroundStyle(colors.text)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 4)
            if let selected {
                GeometryReader { g in
                    if g.size.height > g.size.width {
                        VStack(spacing: 12) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) { tiles(bikes, selected).frame(width: 200) }
                            }
                            .frame(height: 112)
                            BikeDetail(bike: selected, sheet: $sheet)
                        }
                        .padding(.horizontal, 16)
                    } else {
                        HStack(alignment: .top, spacing: 20) {
                            ScrollView { VStack(spacing: 10) { tiles(bikes, selected) } }.frame(width: 260)
                            BikeDetail(bike: selected, sheet: $sheet)
                        }
                        .padding(.horizontal, 16)
                    }
                }
            } else {
                empty
            }
        }
        .background(colors.bg.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("garage_screen")
        .sheet(item: $sheet) { s in sheetView(s).presentationBackground(colors.surface) }
    }

    @ViewBuilder private func tiles(_ bikes: [BikeRecord], _ selected: BikeRecord) -> some View {
        ForEach(bikes, id: \.id) { b in
            BikeTile(bike: b, selected: b.id == selected.id) { Haptics.tap(); selectedId = b.id }
        }
        Button { Haptics.tap(); sheet = .editor(nil) } label: {
            Text("+ Add bike").font(.geist(17, .semibold)).foregroundStyle(colors.textDim)
                .frame(maxWidth: .infinity, minHeight: 64)
                .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(colors.outline, lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain).accessibilityIdentifier("garage_add")
    }

    private var empty: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "scooter").font(.system(size: 44)).foregroundStyle(colors.textDim)
            Text("No bikes yet").font(.geist(22, .semibold)).foregroundStyle(colors.text)
            Hint("Add the bike you ride: trips are counted on its odometer. Your trips so far go to the first bike.")
                .multilineTextAlignment(.center).frame(maxWidth: 460)
            BigButton(title: "Add bike", icon: "plus", style: .main) { Haptics.tap(); sheet = .editor(nil) }
                .frame(maxWidth: 320).accessibilityIdentifier("garage_add")
            Spacer()
        }
        .padding(24).frame(maxWidth: .infinity)
    }

    @ViewBuilder private func sheetView(_ s: GarageSheet) -> some View {
        let repo = garage.repo
        let close = { sheet = nil }
        switch s {
        case .editor(let bike):
            BikeEditor(bike: bike, onSave: { draft, odo in
                if let bike { garage.updateBike(bike.id, draft) } else { selectedId = garage.addBike(draft, odometerKm: odo) }
                close()
            }, onDelete: bike.map { b in { Haptics.confirm(); garage.deleteBike(b.id); selectedId = nil; close() } })
        case .correct(let id):
            if let bike = garage.bikes.first(where: { $0.id == id }) {
                let odo = repo.odometer(id)
                OdometerDialog(title: odo.lastReading == nil ? "\(bike.name)'s odometer" : "Correct \(bike.name)'s odometer",
                               text: odo.lastReading == nil
                                ? "Enter the reading on the bike. From here on, every trip on \(bike.name) adds to it."
                                : "Enter the reading on the bike. GPS drifts a little from it; each correction teaches the app by how much.",
                               prefillKm: odo.estimateKm, onSave: { garage.enterReading(id, km: $0); close() }, onSkip: close)
            }
        case .untracked(let id):
            if let bike = garage.bikes.first(where: { $0.id == id }) {
                UntrackedKmDialog(bike: bike, estimateKm: repo.estimateNow(id)) { _ = garage.addUntrackedKm(id, $0); close() }
            }
        case .level(let id):
            if let bike = garage.bikes.first(where: { $0.id == id }) {
                FuelLevelDialog(bike: bike, estimateKm: repo.estimateNow(id)) { garage.setFuelLevel(id, bars: $0, ofBars: $1, odometerKm: $2); close() }
            }
        case .refuel(let id):
            if let bike = garage.bikes.first(where: { $0.id == id }) {
                RefuelDialog(bike: bike, estimateKm: repo.estimateNow(id), prices: repo.prices()) { garage.logRefuel(id, $0); close() }
            }
        case .price(let fuel):
            FuelPriceDialog(fuel: fuel, current: priceOf(fuel, repo.prices())) { garage.setPrice(fuel, $0); close() }
        case .service(let bikeId, let item):
            ServiceEditor(item: item, estimateKm: repo.estimateNow(bikeId), onSave: { d in
                if let item { garage.updateService(item.id, d) } else { garage.addService(bikeId, d) }
                close()
            }, onDelete: item.map { i in { Haptics.confirm(); garage.deleteService(i.id); close() } })
        case .serviceDone(let item):
            ServiceDoneDialog(item: item, estimateKm: repo.estimateNow(item.bikeId)) { km, cost, note in
                garage.markServiceDone(item.id, odometerKm: km, costRp: cost, note: note); close()
            }
        }
    }
}

private struct BikeTile: View {
    let bike: BikeRecord
    let selected: Bool
    let onTap: () -> Void
    @EnvironmentObject private var garage: GarageStore
    @Environment(\.appColors) private var colors

    var body: some View {
        let fg = selected ? colors.onNavy : colors.text
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(bike.name).font(.geist(20, .semibold)).lineLimit(1)
                    Spacer()
                    if bike.active { Text("Riding").font(.geist(13, .semibold)).accessibilityIdentifier("garage_riding_\(bike.name)") }
                }
                Text(bike.bikeModel.label).font(.geist(14))
                Text(garage.repo.odometer(bike.id).estimateKm.map(formatOdometer) ?? "Odometer not set").font(.geist(14))
            }
            .foregroundStyle(fg)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(selected ? colors.navy : colors.surface, in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(colors.outline, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("garage_bike_\(bike.name)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct BikeDetail: View {
    let bike: BikeRecord
    @Binding var sheet: GarageSheet?
    @EnvironmentObject private var garage: GarageStore
    @Environment(\.appColors) private var colors

    var body: some View {
        let _ = garage.revision
        let repo = garage.repo
        let odo = repo.odometer(bike.id)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(bike.name).font(.geist(28, .semibold)).foregroundStyle(colors.text)
                        Hint("\(bike.bikeModel.label) · \(trimNumber(bike.tankLitres)) L tank · \(bike.fuel.label)")
                    }
                    Spacer()
                    QuietButton(title: "Edit") { sheet = .editor(bike) }.accessibilityIdentifier("garage_edit")
                }
                Text("Odometer").font(.geist(14, .medium)).foregroundStyle(colors.textDim).padding(.top, 8)
                Text(odo.estimateKm.map(formatOdometer) ?? "Not set").font(.geist(34, .semibold)).foregroundStyle(colors.text)
                    .lineLimit(1).minimumScaleFactor(0.6)
                    .accessibilityIdentifier("garage_odometer_value")
                HStack(spacing: 8) {
                    if odo.lastReading != nil {
                        QuietButton(title: "Add untracked km") { sheet = .untracked(bike.id) }.accessibilityIdentifier("garage_untracked")
                    }
                    QuietButton(title: odo.lastReading == nil ? "Set odometer" : "Correct") { sheet = .correct(bike.id) }
                        .accessibilityIdentifier("garage_correct")
                }
                factorLine(odo)
                if bike.active {
                    Hint("Riding this bike: new trips are counted on it.")
                } else {
                    BigButton(title: "Ride \(bike.name)", icon: "scooter", style: .main) { Haptics.confirm(); garage.activate(bike.id) }
                        .accessibilityIdentifier("garage_ride")
                }
                FuelSection(bike: bike, sheet: $sheet).padding(.top, 12)
                ServicesSection(bike: bike, sheet: $sheet).padding(.top, 12)
                let readings = repo.readings(bike.id)
                if !readings.isEmpty {
                    Text("Odometer log").font(.geist(18, .semibold)).foregroundStyle(colors.text).padding(.top, 12)
                    ForEach(readings, id: \.id) { r in
                        Divider().overlay(colors.outline)
                        HStack {
                            Text(readingDate(r.timeMs)).font(.geist(14)).foregroundStyle(colors.textDim).frame(width: 110, alignment: .leading)
                            Text(formatOdometer(r.km)).font(.geist(17, .medium)).foregroundStyle(colors.text)
                            Spacer()
                            Text(readingLabel(r)).font(.geist(14)).foregroundStyle(colors.textDim)
                        }
                        .frame(minHeight: 40)
                        .accessibilityIdentifier("garage_reading")
                    }
                }
            }
            .padding(.bottom, 24)
        }
    }

    @ViewBuilder private func factorLine(_ odo: OdometerState) -> some View {
        if odo.lastReading != nil {
            let pct = (odo.factor - 1) * 100
            Hint(abs(pct) < 0.05
                 ? "Correct the odometer now and then (at a fill-up): the app learns how far GPS drifts from it."
                 : String(format: "GPS reads %.1f%% %@; trips are scaled to match.", abs(pct), pct > 0 ? "short" : "long"))
                .accessibilityIdentifier("garage_factor")
        }
    }

    private func readingLabel(_ r: OdometerReading) -> String {
        switch r.readingKind {
        case .set: "First reading"
        case .correction: "Corrected"
        case .away: "Away +\(formatOdometer(r.awayKm))"
        case .untracked: "Untracked +\(formatOdometer(r.awayKm))"
        }
    }
}

/// The last segment of the tank: range and number turn red at or below it.
let lowFuelFraction = 0.2

/// "from 3 fill-ups + 2 rides", or "(starting value)".
func kmplSource(_ c: Consumption) -> String {
    guard c.learned else { return "(starting value)" }
    var parts: [String] = []
    if c.tanksUsed > 0 { parts.append(c.tanksUsed == 1 ? "1 fill-up" : "\(c.tanksUsed) fill-ups") }
    if c.dashUsed > 0 { parts.append(c.dashUsed == 1 ? "1 ride" : "\(c.dashUsed) rides") }
    return "from " + parts.joined(separator: " + ")
}

/// " · range at 34.0 km/L" when recent tanks are below the long run (the range uses those).
func cautiousNote(_ f: FuelState) -> String {
    f.consumption.cautiousKmPerL < f.consumption.kmPerL - 0.05 ? " · range at \(formatKmPerL(f.consumption.cautiousKmPerL))" : ""
}

/// "4.1 L of 6.6 L · 36.4 km/L from 3 fill-ups", or why the level is unknown.
func fuelLine(_ f: FuelState) -> String {
    let kmpl = formatKmPerL(f.consumption.kmPerL) + " " + kmplSource(f.consumption)
    if let l = f.level.litres {
        return "\(formatLitres(l)) of \(trimNumber(f.level.tankLitres)) L" + (f.level.fromGauge ? " (from the gauge)" : "") + " · \(kmpl)" + cautiousNote(f)
    }
    if f.level.unknownBecause == .away { return "Unknown after away km: set the level or fill up · \(kmpl)" }
    return "Set the level from the gauge, or log a full fill-up · \(kmpl)"
}

private struct FuelSection: View {
    let bike: BikeRecord
    @Binding var sheet: GarageSheet?
    @EnvironmentObject private var garage: GarageStore
    @Environment(\.appColors) private var colors
    @State private var showCost = false
    @State private var armed: Int64?

    var body: some View {
        let repo = garage.repo
        let f = repo.fuel(bike.id)
        let prices = repo.prices()
        VStack(alignment: .leading, spacing: 8) {
            Text("Fuel").font(.geist(18, .semibold)).foregroundStyle(colors.text)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Range").font(.geist(14, .medium)).foregroundStyle(colors.textDim)
                    let low = (f.level.fraction ?? 1) <= lowFuelFraction
                    Text(f.level.cautiousRangeKm.map { "\($0) km" } ?? "—").font(.geist(34, .semibold))
                        .foregroundStyle(low && f.level.cautiousRangeKm != nil ? colors.red : colors.text)
                        .accessibilityIdentifier("garage_range")
                }
                Spacer()
                BigButton(title: "Refuel", icon: "fuelpump", style: .main) { Haptics.tap(); sheet = .refuel(bike.id) }
                    .frame(maxWidth: 220).accessibilityIdentifier("garage_refuel")
            }
            Hint(fuelLine(f)).accessibilityIdentifier("garage_fuel_line")
            if let since = f.level.sinceRefuelKm {
                Hint("\(formatOdometer(since)) since the last fill-up").accessibilityIdentifier("garage_since_refuel")
            }
            HStack(spacing: 8) {
                QuietButton(title: "Set fuel level") { sheet = .level(bike.id) }.accessibilityIdentifier("garage_set_level")
                QuietButton(title: showCost ? "Hide costs" : "Calculate refill") { showCost.toggle() }.accessibilityIdentifier("garage_cost_toggle")
            }
            if showCost { costPanel(f) }
            Text("Fuel prices").font(.geist(18, .semibold)).foregroundStyle(colors.text).padding(.top, 8)
            Hint("Tap one when the rate changes.")
            ForEach(FuelType.allCases, id: \.self) { type in
                let own = prices.first { $0.fuelType == type.rawValue }
                Button { Haptics.tap(); sheet = .price(type) } label: {
                    HStack {
                        Text(type.label).font(.geist(17)).foregroundStyle(colors.text)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 0) {
                            Text("\(formatRupiah(priceOf(type, prices))) / L").font(.geist(17, .medium)).foregroundStyle(colors.text)
                                .accessibilityIdentifier("garage_price_value_\(type.rawValue)")
                            Text(own.map { "updated \(formatDayLong($0.updatedAtMs))" } ?? "default").font(.geist(13)).foregroundStyle(colors.textDim)
                        }
                    }
                    .frame(minHeight: 56).contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityIdentifier("garage_price_\(type.rawValue)")
            }
            if !f.refuels.isEmpty {
                Text("Refuel log").font(.geist(18, .semibold)).foregroundStyle(colors.text).padding(.top, 8)
                let measures = Dictionary(f.consumption.measures.map { ($0.endRefuelId, $0) }, uniquingKeysWith: { a, _ in a })
                ForEach(f.refuels, id: \.id) { r in
                    Divider().overlay(colors.outline)
                    HStack(spacing: 8) {
                        Text(readingDate(r.timeMs)).font(.geist(14)).foregroundStyle(colors.textDim).frame(width: 100, alignment: .leading)
                        Text(formatOdometer(r.odometerKm)).font(.geist(16, .medium)).foregroundStyle(colors.text)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 0) {
                            Text("\(r.full ? "Full" : "Partial") \(formatLitres(r.litres))").font(.geist(14)).foregroundStyle(colors.text)
                            if let m = measures[r.id] {
                                Text(formatKmPerL(m.kmPerL) + (m.used ? "" : " (skipped)")).font(.geist(13)).foregroundStyle(colors.textDim)
                            }
                        }
                        Button {
                            if armed == r.id { Haptics.confirm(); garage.deleteRefuel(r.id); armed = nil } else {
                                Haptics.tap(); armed = r.id
                                Task { try? await Task.sleep(nanoseconds: 3_000_000_000); if armed == r.id { armed = nil } }
                            }
                        } label: {
                            Image(systemName: "trash").font(.system(size: 18)).foregroundStyle(armed == r.id ? colors.red : colors.textDim)
                                .frame(width: 56, height: 56).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(armed == r.id ? "Tap again to delete" : "Delete fill-up")
                        .accessibilityIdentifier("garage_refuel_delete")
                    }
                    .accessibilityIdentifier("garage_refuel_row")
                }
            }
        }
    }

    private func costPanel(_ f: FuelState) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let toFill = f.level.toFillLitres {
                Hint("Next refill ≈ \(formatLitres(toFill)) × \(formatRupiah(f.pricePerL)) = \(formatRupiah(toFill * f.pricePerL))", color: colors.text)
                    .accessibilityIdentifier("garage_refill_estimate")
            } else {
                Hint("Next refill: needs a full fill-up first", color: colors.text)
            }
            let s = spend(f.refuels, nowMs: SystemWallClock().nowMs(), zone: .current)
            Hint("Last 7 days \(formatRupiah(s.last7Days)) · this month \(formatRupiah(s.thisMonth)) · last month \(formatRupiah(s.lastMonth))", color: colors.text)
            if let c = costPerKm(f.refuels) { Hint("\(formatRupiah(c)) per km") }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("garage_cost")
    }
}

private struct ServicesSection: View {
    let bike: BikeRecord
    @Binding var sheet: GarageSheet?
    @EnvironmentObject private var garage: GarageStore
    @Environment(\.appColors) private var colors

    var body: some View {
        let dues = garage.repo.services(bike.id)
        let history = garage.repo.serviceHistory(bike.id)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Service").font(.geist(18, .semibold)).foregroundStyle(colors.text)
                Spacer()
                QuietButton(title: "Add item") { sheet = .service(bike.id, nil) }.accessibilityIdentifier("garage_service_add")
            }
            if dues.isEmpty { Hint("Add what you service (oil, CVT, tyres…) with your own intervals, and dates like vehicle tax.") }
            ForEach(dues, id: \.item.id) { due in
                Divider().overlay(colors.outline)
                HStack(spacing: 10) {
                    Button { Haptics.tap(); sheet = .service(bike.id, due.item) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(due.item.name).font(.geist(17, .medium)).foregroundStyle(colors.text)
                            Text(intervalText(due.item, zone: .current)).font(.geist(14)).foregroundStyle(colors.textDim)
                        }
                        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("garage_service_\(due.item.name)")
                    Text(dueText(due)).font(.geist(15, .medium))
                        .foregroundStyle(due.level == .overdue ? colors.red : due.level == .soon ? colors.amber : colors.textDim)
                        .accessibilityIdentifier("garage_service_due_\(due.item.name)")
                    QuietButton(title: "Done") { sheet = .serviceDone(due.item) }.accessibilityIdentifier("garage_service_done_\(due.item.name)")
                }
            }
            if !history.isEmpty {
                Text("Service history").font(.geist(18, .semibold)).foregroundStyle(colors.text).padding(.top, 8)
                ForEach(history.reversed(), id: \.id) { r in
                    Divider().overlay(colors.outline)
                    HStack {
                        Text(readingDate(r.timeMs)).font(.geist(14)).foregroundStyle(colors.textDim).frame(width: 100, alignment: .leading)
                        Text(r.itemName).font(.geist(16, .medium)).foregroundStyle(colors.text)
                        Spacer()
                        Text([r.odometerKm.map(formatOdometer), r.costRp.map(formatRupiah), r.note].compactMap { $0 }.joined(separator: " · "))
                            .font(.geist(14)).foregroundStyle(colors.textDim).lineLimit(2)
                    }
                    .frame(minHeight: 40)
                }
            }
        }
    }
}
