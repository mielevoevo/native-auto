import SwiftUI

// The Garage's dialogs (Android `GarageDialogs.kt`, `FuelUi.kt` dialogs, `ServiceUi.kt` editors), as sheets: a title,
// Cancel / Save in the header (Save off until the input is valid), the fields below. Gloves: 56 pt rows and buttons.

/// A form sheet: title, Cancel and the main action on top, scrolling content, optional footer (delete).
struct FormSheet<Content: View>: View {
    let title: String
    var saveTitle = "Save"
    var cancelTitle = "Cancel"
    let canSave: Bool
    let onSave: () -> Void
    var onCancel: (() -> Void)?
    let id: String
    @ViewBuilder var content: () -> Content
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button(cancelTitle) { if let onCancel { onCancel() } else { dismiss() } }
                    .font(.geist(17)).foregroundStyle(colors.textDim).frame(minWidth: 56, minHeight: 56)
                    .accessibilityIdentifier("\(id)_cancel")
                Spacer()
                Text(title).font(.geist(18, .semibold)).foregroundStyle(colors.text).lineLimit(1).minimumScaleFactor(0.8)
                Spacer()
                Button(saveTitle) { Haptics.confirm(); onSave() }
                    .font(.geist(17, .semibold)).foregroundStyle(canSave ? colors.accent : colors.textDim.opacity(0.5))
                    .frame(minWidth: 56, minHeight: 56).disabled(!canSave)
                    .accessibilityIdentifier("\(id)_save")
            }
            .padding(.horizontal, 16).padding(.top, 6)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) { content() }
                    .padding(20).frame(maxWidth: 640, alignment: .leading).frame(maxWidth: .infinity)
            }
        }
        .background(colors.surface.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }
}

/// A labelled number field; red outline when the text doesn't parse.
struct NumberField: View {
    let label: String
    @Binding var text: String
    var invalid = false
    var decimal = false
    let id: String
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.geist(14, .medium)).foregroundStyle(invalid ? colors.red : colors.textDim)
            TextField("", text: $text)
                .keyboardType(decimal ? .decimalPad : .numberPad)
                .font(.geist(20, .medium)).foregroundStyle(colors.text)
                .padding(.horizontal, 14).frame(minHeight: 56)
                .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(invalid ? colors.red : .clear, lineWidth: 1.5))
                .accessibilityIdentifier(id)
                .accessibilityLabel(label)
        }
    }
}

struct TextFieldRow: View {
    let label: String
    @Binding var text: String
    let id: String
    @Environment(\.appColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.geist(14, .medium)).foregroundStyle(colors.textDim)
            TextField("", text: $text)
                .font(.geist(18)).foregroundStyle(colors.text)
                .padding(.horizontal, 14).frame(minHeight: 56)
                .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier(id).accessibilityLabel(label)
        }
    }
}

/// A switch row, the whole row a target.
struct SwitchRow: View {
    let label: String
    @Binding var on: Bool
    let id: String
    @Environment(\.appColors) private var colors

    var body: some View {
        Toggle(isOn: Binding(get: { on }, set: { Haptics.toggle(); on = $0 })) {
            Text(label).font(.geist(17, .medium)).foregroundStyle(colors.text)
        }
        .tint(colors.navy)
        .frame(minHeight: 56)
        .accessibilityIdentifier(id)
    }
}

struct Hint: View {
    let text: String
    var color: Color?
    @Environment(\.appColors) private var colors
    init(_ text: String, color: Color? = nil) { self.text = text; self.color = color }
    var body: some View { Text(text).font(.geist(15)).foregroundStyle(color ?? colors.textDim).fixedSize(horizontal: false, vertical: true) }
}

/// 6.6 → "6.6", 7.0 → "7".
func trimNumber(_ v: Double) -> String { v == v.rounded(.down) ? String(Int64(v)) : String(format: "%.1f", v) }
/// 12500.0 → "12500" (typed back into a field).
func wholeNumber(_ v: Double) -> String { String(Int64(v.rounded())) }

/// "17 Nov 2026".
func readingDate(_ ms: Int64) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "d MMM yyyy"
    return f.string(from: dateOf(ms))
}

// MARK: - Bike editor

struct BikeEditor: View {
    let bike: BikeRecord?
    let onSave: (BikeDraft, Double?) -> Void
    var onDelete: (() -> Void)?
    @State private var name = ""
    @State private var model = BikeModel.nmaxGen1
    @State private var fuel = FuelType.pertamax
    @State private var tank = ""
    @State private var reserve = ""
    @State private var kmPerL = ""
    @State private var odometer = ""
    @State private var deleteArmed = false
    @Environment(\.appColors) private var colors

    var body: some View {
        let tankL = parseNumber(tank).flatMap { (1...30).contains($0) ? $0 : nil }
        let reserveL = parseNumber(reserve).flatMap { v in tankL.flatMap { v >= 0 && v < $0 ? v : nil } }
        let kmL = parseNumber(kmPerL).flatMap { kmplPlausible.contains($0) ? $0 : nil }
        let odoKm = parseNumber(odometer, thousands: true).flatMap { $0 >= 0 ? $0 : nil }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let ok = !trimmed.isEmpty && tankL != nil && reserveL != nil && kmL != nil && (odometer.isEmpty || odoKm != nil)
        FormSheet(title: bike.map { "Edit \($0.name)" } ?? "Add bike", canSave: ok, onSave: {
            onSave(BikeDraft(name: trimmed, model: model, tankLitres: tankL!, reserveLitres: reserveL!, fuel: fuel, startKmPerL: kmL!), odoKm)
        }, id: "garage_editor") {
            TextFieldRow(label: "Name (e.g. Gen 1)", text: $name, id: "garage_name")
            Text("Model").font(.geist(16, .medium)).foregroundStyle(colors.text)
            Segments(options: BikeModel.allCases, selection: Binding(get: { model }, set: { m in
                model = m; tank = trimNumber(m.tankLitres); reserve = trimNumber(m.reserveLitres)
            })) { $0.shortLabel }
            Text("Fuel").font(.geist(16, .medium)).foregroundStyle(colors.text)
            Segments(options: FuelType.allCases, selection: $fuel) { $0.grade }
            HStack(spacing: 12) {
                NumberField(label: "Tank (L)", text: $tank, invalid: tankL == nil, decimal: true, id: "garage_tank")
                NumberField(label: "Reserve (L)", text: $reserve, invalid: reserveL == nil, decimal: true, id: "garage_reserve")
            }
            NumberField(label: "Starting km/L", text: $kmPerL, invalid: kmL == nil, decimal: true, id: "garage_kmpl")
            if bike == nil {
                NumberField(label: "Odometer now (km, optional)", text: $odometer, invalid: !odometer.isEmpty && odoKm == nil, id: "garage_odometer")
            }
            if let onDelete {
                BigButton(title: deleteArmed ? "Tap again to delete" : "Delete bike", icon: "trash", style: deleteArmed ? .danger : .quiet) {
                    if deleteArmed { onDelete() } else {
                        Haptics.tap(); deleteArmed = true
                        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); deleteArmed = false }
                    }
                }
                .accessibilityIdentifier("garage_delete")
                Hint("Its trips stay in the history, without a bike.")
            }
        }
        .onAppear {
            if let b = bike {
                name = b.name; model = b.bikeModel; fuel = b.fuel; tank = trimNumber(b.tankLitres); reserve = trimNumber(b.reserveLitres)
                kmPerL = trimNumber(b.startKmPerL)
            } else {
                tank = trimNumber(model.tankLitres); reserve = trimNumber(model.reserveLitres); kmPerL = trimNumber(defaultKmPerL)
            }
        }
    }
}

// MARK: - Odometer, untracked km, level, prices

/// One reading: correcting from the Garage, or the away prompt ("Gen 2's odometer now?").
struct OdometerDialog: View {
    let title: String
    let text: String
    let prefillKm: Double?
    var skipLabel = "Cancel"
    let onSave: (Double) -> Void
    let onSkip: () -> Void
    @State private var value = ""

    var body: some View {
        let km = parseNumber(value, thousands: true).flatMap { $0 >= 0 ? $0 : nil }
        FormSheet(title: title, cancelTitle: skipLabel, canSave: km != nil, onSave: { onSave(km!) }, onCancel: onSkip, id: "garage_odo") {
            Hint(text)
            NumberField(label: "Odometer (km)", text: $value, invalid: !value.isEmpty && km == nil, id: "garage_odo_input")
        }
        .onAppear { value = prefillKm.map(wholeNumber) ?? "" }
    }
}

struct UntrackedKmDialog: View {
    let bike: BikeRecord
    let estimateKm: Double?
    let onSave: (Double) -> Void
    @State private var value = ""

    var body: some View {
        let km = parseNumber(value, thousands: true).flatMap { $0 > 0 && $0 <= 5_000 ? $0 : nil }
        FormSheet(title: "Add untracked km · \(bike.name)", saveTitle: "Add", canSave: km != nil, onSave: { onSave(km!) }, id: "garage_untracked_dialog") {
            Hint("Rode \(bike.name) without the app? Add the km here: they count for fuel and servicing, but not as trips.")
            NumberField(label: "Km ridden", text: $value, invalid: !value.isEmpty && km == nil, id: "garage_untracked_input")
            if let km, let estimateKm { Hint("Odometer becomes \(formatOdometer(estimateKm + km))") }
        }
    }
}

/// 12b: the bars lit on the bike's gauge (6 on the NMAX gen 1, 8 otherwise) and the odometer.
struct FuelLevelDialog: View {
    let bike: BikeRecord
    let estimateKm: Double?
    let onSave: (Int, Int, Double) -> Void
    @State private var bars: Int?
    @State private var odo = ""
    @Environment(\.appColors) private var colors

    var body: some View {
        let of = gaugeBars(bike.bikeModel)
        let km = parseNumber(odo, thousands: true).flatMap { $0 >= 0 ? $0 : nil }
        FormSheet(title: "Set fuel level · \(bike.name)", canSave: bars != nil && km != nil, onSave: { onSave(bars!, of, km!) }, id: "garage_level_dialog") {
            Hint("How many bars does the gauge show? The range works from there, on the low side, until the next full fill-up.")
            HStack(spacing: 0) {
                ForEach(0...of, id: \.self) { b in
                    let on = bars == b
                    Button { Haptics.tick(); bars = b } label: {
                        Text("\(b)").font(.geist(17, .semibold)).foregroundStyle(on ? colors.onNavy : colors.text)
                            .frame(maxWidth: .infinity, minHeight: 56).background(on ? colors.navy : .clear).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("garage_level_bar_\(b)")
                }
            }
            .clipShape(Capsule()).overlay(Capsule().strokeBorder(colors.outline, lineWidth: 1))
            if let b = bars {
                Hint("About \(formatLitres(gaugeLitres(b, of, tankLitres: bike.tankLitres))) of \(trimNumber(bike.tankLitres)) L")
                    .accessibilityIdentifier("garage_level_litres")
            }
            NumberField(label: "Odometer (km)", text: $odo, invalid: !odo.isEmpty && km == nil, id: "garage_level_odo")
        }
        .onAppear { odo = estimateKm.map(wholeNumber) ?? "" }
    }
}

struct FuelPriceDialog: View {
    let fuel: FuelType
    let current: Double
    let onSave: (Double) -> Void
    @State private var value = ""

    var body: some View {
        let price = parseNumber(value, thousands: true).flatMap { (1_000...100_000).contains($0) ? $0 : nil }
        FormSheet(title: "\(fuel.label) price", canSave: price != nil, onSave: { onSave(price!) }, id: "garage_price_dialog") {
            Hint("Rupiah per litre. Used for the next fill-ups and the refill estimate; past fill-ups keep their price.")
            NumberField(label: "Rp / L", text: $value, invalid: !value.isEmpty && price == nil, id: "garage_price_input")
        }
        .onAppear { value = wholeNumber(current) }
    }
}

/// 12b: the bike's own average for the ride just ended (its dash), optional.
struct DashKmPerLDialog: View {
    let bikeName: String
    let onSave: (Double) -> Void
    let onSkip: () -> Void
    @State private var value = ""

    var body: some View {
        let kmpl = parseNumber(value).flatMap { kmplPlausible.contains($0) ? $0 : nil }
        FormSheet(title: "\(bikeName)'s km/L", cancelTitle: "Skip", canSave: kmpl != nil, onSave: { onSave(kmpl!) }, onCancel: onSkip, id: "kmpl_dialog") {
            Hint("What average did the dash show for this ride? Optional: it helps the range estimate.")
            NumberField(label: "km/L", text: $value, invalid: !value.isEmpty && kmpl == nil, decimal: true, id: "kmpl_value")
        }
    }
}

// MARK: - Refuel

/// The pump, a few taps: full refill (the default) or not; what was paid in Rp or litres, the other worked out from the
/// price per litre (prefilled with the fuel's last price); the odometer, prefilled with the estimate.
struct RefuelDialog: View {
    let bike: BikeRecord
    let estimateKm: Double?
    let prices: [FuelPrice]
    let onSave: (RefuelDraft) -> Void
    @State private var full = true
    @State private var fuel = FuelType.pertamax
    @State private var mode = AmountMode.rupiah
    @State private var amount = ""
    @State private var price = ""
    @State private var odometer = ""

    var body: some View {
        let amountValue = mode == .rupiah ? parseNumber(amount, thousands: true) : parseNumber(amount)
        let priceValue = parseNumber(price, thousands: true).flatMap { (1_000...100_000).contains($0) ? $0 : nil }
        let amounts = refuelAmounts(mode, amount: amountValue, pricePerL: priceValue).flatMap { refuelLitresFit($0.litres, tankLitres: bike.tankLitres) ? $0 : nil }
        let odoKm = parseNumber(odometer, thousands: true)
        FormSheet(title: "Refuel · \(bike.name)", canSave: amounts != nil && odoKm != nil, onSave: {
            onSave(RefuelDraft(full: full, litres: amounts!.litres, pricePerL: priceValue!, totalRp: amounts!.rp, fuel: fuel, odometerKm: odoKm!))
        }, id: "garage_refuel_dialog") {
            SwitchRow(label: "Full refill", on: $full, id: "garage_refuel_full")
            Segments(options: FuelType.allCases, selection: Binding(get: { fuel }, set: { fuel = $0; price = wholeNumber(priceOf($0, prices)) })) { $0.grade }
            Segments(options: AmountMode.allCases, selection: Binding(get: { mode }, set: { mode = $0; amount = "" })) { $0 == .rupiah ? "Paid (Rp)" : "Litres" }
            HStack(spacing: 12) {
                NumberField(label: mode == .rupiah ? "Paid (Rp)" : "Litres", text: $amount, invalid: !amount.isEmpty && amounts == nil,
                            decimal: mode == .litres, id: "garage_refuel_amount")
                NumberField(label: "Rp per litre", text: $price, invalid: priceValue == nil, id: "garage_refuel_price")
            }
            Hint(amounts.map { mode == .rupiah ? "= \(formatLitres($0.litres))" : "= \(formatRupiah($0.rp))" } ?? "Tank holds \(formatLitres(bike.tankLitres))")
                .accessibilityIdentifier("garage_refuel_result")
            NumberField(label: "Odometer (km)", text: $odometer, invalid: !odometer.isEmpty && odoKm == nil, id: "garage_refuel_odo")
        }
        .onAppear {
            fuel = bike.fuel
            price = wholeNumber(priceOf(bike.fuel, prices))
            odometer = estimateKm.map(wholeNumber) ?? ""
        }
    }
}

// MARK: - Services

struct ServiceEditor: View {
    let item: ServiceItem?
    let estimateKm: Double?
    let onSave: (ServiceDraft) -> Void
    var onDelete: (() -> Void)?
    @State private var kind = ServiceKind.distance
    @State private var name = ""
    @State private var km = ""
    @State private var months = ""
    @State private var due = ""
    @State private var lastKm = ""
    @State private var deleteArmed = false
    @Environment(\.appColors) private var colors

    var body: some View {
        let zone = TimeZone.current
        let kmValue = parseNumber(km, thousands: true).flatMap { $0 > 0 ? $0 : nil }
        let monthsValue = Int(months.trimmingCharacters(in: .whitespaces)).flatMap { $0 > 0 && $0 <= 120 ? $0 : nil }
        let dueValue = parseDate(due, zone: zone)
        let lastKmValue = parseNumber(lastKm, thousands: true).flatMap { $0 >= 0 ? $0 : nil }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let ok: Bool = {
            guard !trimmed.isEmpty, months.isEmpty || monthsValue != nil else { return false }
            if kind == .date { return dueValue != nil }
            return (km.isEmpty || kmValue != nil) && (lastKm.isEmpty || lastKmValue != nil)
        }()
        FormSheet(title: item.map { "Edit \($0.name)" } ?? "Add service item", canSave: ok, onSave: {
            onSave(ServiceDraft(name: trimmed, kind: kind, intervalKm: kind == .distance ? kmValue : nil, intervalMonths: monthsValue,
                                dueDateMs: kind == .date ? dueValue : nil, lastDoneKm: kind == .distance ? lastKmValue : nil))
        }, id: "garage_service_editor") {
            Segments(options: ServiceKind.allCases, selection: $kind) { $0 == .distance ? "By km / months" : "By date" }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if kind == .distance {
                        ForEach(serviceNames, id: \.self) { n in Chip(title: n, on: name == n) { name = n } }
                    } else {
                        ForEach(dateNames, id: \.name) { d in Chip(title: d.name, on: name == d.name) { name = d.name; months = String(d.months) } }
                    }
                }
            }
            TextFieldRow(label: "Name", text: $name, id: "garage_service_name")
            if kind == .distance {
                HStack(spacing: 12) {
                    NumberField(label: "Every (km)", text: $km, invalid: !km.isEmpty && kmValue == nil, id: "garage_service_km")
                    NumberField(label: "Every (months)", text: $months, invalid: !months.isEmpty && monthsValue == nil, id: "garage_service_months")
                }
                if item == nil {
                    NumberField(label: "Last done at (km)", text: $lastKm, invalid: !lastKm.isEmpty && lastKmValue == nil, id: "garage_service_last_km")
                }
                Hint("Due at whichever comes first. Leave both empty to keep it as a name only.")
            } else {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Due (d/m/yyyy)").font(.geist(14, .medium)).foregroundStyle(!due.isEmpty && dueValue == nil ? colors.red : colors.textDim)
                        TextField("", text: $due).keyboardType(.numbersAndPunctuation).font(.geist(20, .medium))
                            .padding(.horizontal, 14).frame(minHeight: 56)
                            .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier("garage_service_due")
                    }
                    NumberField(label: "Every (months)", text: $months, invalid: !months.isEmpty && monthsValue == nil, id: "garage_service_months")
                }
            }
            if let onDelete {
                BigButton(title: deleteArmed ? "Tap again to delete" : "Delete item", icon: "trash", style: deleteArmed ? .danger : .quiet) {
                    if deleteArmed { onDelete() } else {
                        Haptics.tap(); deleteArmed = true
                        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); deleteArmed = false }
                    }
                }
                .accessibilityIdentifier("garage_service_delete")
            }
        }
        .onAppear {
            guard let s = item else { if let e = estimateKm { lastKm = wholeNumber(e) }; return }
            kind = s.serviceKind; name = s.name
            km = s.intervalKm.map(wholeNumber) ?? ""
            months = s.intervalMonths.map(String.init) ?? ""
            due = s.dueDateMs.map { formatDate($0, zone: .current) } ?? ""
        }
    }
}

/// "Mark done": the odometer (prefilled), and an optional cost and note.
struct ServiceDoneDialog: View {
    let item: ServiceItem
    let estimateKm: Double?
    let onSave: (Double?, Double?, String?) -> Void
    @State private var odo = ""
    @State private var cost = ""
    @State private var note = ""

    var body: some View {
        let odoValue = parseNumber(odo, thousands: true).flatMap { $0 >= 0 ? $0 : nil }
        let costValue = parseNumber(cost, thousands: true).flatMap { $0 >= 0 ? $0 : nil }
        let ok = (odo.isEmpty || odoValue != nil) && (cost.isEmpty || costValue != nil)
        FormSheet(title: "\(item.name) done", canSave: ok, onSave: {
            onSave(odoValue, costValue, note.trimmingCharacters(in: .whitespaces).nonEmpty)
        }, id: "garage_service_done") {
            HStack(spacing: 12) {
                NumberField(label: "Odometer (km)", text: $odo, invalid: !odo.isEmpty && odoValue == nil, id: "garage_service_done_odo")
                NumberField(label: "Cost (Rp, optional)", text: $cost, invalid: !cost.isEmpty && costValue == nil, id: "garage_service_done_cost")
            }
            TextFieldRow(label: "Note (optional)", text: $note, id: "garage_service_done_note")
        }
        .onAppear { odo = estimateKm.map(wholeNumber) ?? "" }
    }
}

// MARK: - Prompts at the dashboard root

/// The Garage's questions: "Gen 2's odometer now?" after days away; "Which bike?" (debug builds).
struct GaragePromptSheet: View {
    let prompt: GaragePrompt
    @EnvironmentObject private var garage: GarageStore
    @Environment(\.appColors) private var colors

    var body: some View {
        switch prompt {
        case .odometerNow(let bike, let estimate):
            OdometerDialog(title: "\(bike.name)'s odometer now?",
                           text: "\(bike.name) hasn't been ridden with the app for a few days. Enter its odometer, so km ridden elsewhere are counted.",
                           prefillKm: estimate, skipLabel: "Skip", onSave: { garage.answerOdometerPrompt($0) }, onSkip: { garage.answerOdometerPrompt(nil) })
        case .whichBike(let bikes, let activeId, let distanceKm):
            VStack(alignment: .leading, spacing: 16) {
                Text("Which bike?").font(.geist(22, .semibold)).foregroundStyle(colors.text)
                Hint("This trip starts \(Int64(distanceKm.rounded())) km from where \(bikes.first { $0.id == activeId }?.name ?? "your bike") was last ridden.")
                ForEach(bikes, id: \.id) { b in
                    BigButton(title: b.name, icon: "scooter", style: b.id == activeId ? .main : .quiet) { garage.answerWhichBike(b.id) }
                }
                BigButton(title: "Not now", icon: "xmark", style: .quiet) { garage.dismissPrompt() }
            }
            .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(colors.surface.ignoresSafeArea())
        }
    }
}
