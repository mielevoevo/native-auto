import Foundation

// Port of Android `garage/Fuel.kt` (11b, 12b, 13a). Distances come from the odometer (readings, corrections,
// untracked km), never from GPS km directly, so a ride without the app can't skew km/L. km/L is learned from
// full-to-full fill-ups; the tank level is walked forward from the last full fill (or a gauge reading); away km make
// the level unknown until the next full fill.

/// Bars on the bike's fuel gauge: NMAX gen 1 has 6, gen 2 8.
func gaugeBars(_ model: BikeModel) -> Int { model == .nmaxGen1 ? 6 : 8 }

/// Litres for `bars` lit out of `ofBars`. A bar stays lit until it's empty, so n bars means between n−1 and n: the
/// cautious middle of the last bar, n − ½. No bars = 0.
func gaugeLitres(_ bars: Int, _ ofBars: Int, tankLitres: Double) -> Double {
    if bars <= 0 || ofBars <= 0 { return 0 }
    return min(max((Double(bars) - 0.5) / Double(ofBars), 0), 1) * tankLitres
}

/// Rp per litre for `fuel`: the last one logged, else the built-in default.
func priceOf(_ fuel: FuelType, _ prices: [FuelPrice]) -> Double {
    prices.first { $0.fuelType == fuel.rawValue }?.pricePerL ?? fuel.defaultPricePerL
}

// MARK: - Consumption learning

let kmplWeight = 0.3
let kmplFirstWeight = 0.5
let kmplPlausible = 10.0...100.0
/// 12b: how far one dash figure moves the estimate. Smaller than a tank: dashes read optimistic.
let dashWeight = 0.15
/// 12b: the cautious figure looks at this many recent tanks.
let recentTanks = 3
/// Once learning has started, a measure this far (±) from the estimate is an outlier.
let kmplOutlier = 0.35

/// One full-to-full span. `used` false: skipped (away km in it) or an outlier.
struct TankMeasure: Equatable {
    var endRefuelId: Int64
    var endTimeMs: Int64
    var km: Double
    var litres: Double
    var used: Bool
    var kmPerL: Double { litres > 0 ? km / litres : 0 }
}

struct Consumption: Equatable {
    /// The learned km/L (the bike's starting value until the first full-to-full span).
    var kmPerL: Double
    /// Oldest first.
    var measures: [TankMeasure]
    var dashUsed: Int = 0

    var learned: Bool { measures.contains { $0.used } || dashUsed > 0 }
    var tanksUsed: Int { measures.filter(\.used).count }

    /// 12b, the range's figure: the lower of the long-run estimate and the mean of the last `recentTanks` tanks.
    var cautiousKmPerL: Double {
        let recent = measures.filter(\.used).suffix(recentTanks)
        if recent.isEmpty { return kmPerL }
        return min(kmPerL, recent.map(\.kmPerL).reduce(0, +) / Double(recent.count))
    }
}

/// Oldest first; same-time entries by odometer.
private func chronological(_ refuels: [RefuelRecord]) -> [RefuelRecord] {
    refuels.sorted { ($0.timeMs, $0.odometerKm, $0.id) < ($1.timeMs, $1.odometerKm, $1.id) }
}

/// Walks the fill-ups oldest first. Between two full fills, km = odometer delta and litres = everything added after the
/// first, up to and including the second. Spans with an away reading in them are skipped.
func learnConsumption(startKmPerL: Double, refuels: [RefuelRecord], awayTimesMs: [Int64], dash: [DashKmPerL] = []) -> Consumption {
    var estimate = startKmPerL
    var lastFull: RefuelRecord?
    var litres = 0.0
    var accepted = 0
    var dashUsed = 0
    var measures: [TankMeasure] = []
    var pending = dash.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) }
    func takeDash(_ untilMs: Int64) {
        while let first = pending.first, first.timeMs <= untilMs {
            pending.removeFirst()
            let v = first.kmPerL
            let learning = accepted + dashUsed > 0
            if kmplPlausible.contains(v) && (!learning || abs(v / estimate - 1) <= kmplOutlier) {
                estimate += dashWeight * (v - estimate)
                dashUsed += 1
            }
        }
    }
    for r in chronological(refuels) {
        takeDash(r.timeMs)
        guard let prev = lastFull else {
            if r.full { lastFull = r }
            continue
        }
        litres += r.litres
        if !r.full { continue }
        let km = r.odometerKm - prev.odometerKm
        let away = awayTimesMs.contains { $0 > prev.timeMs && $0 <= r.timeMs }
        if km > 0 && litres > 0 && !away {
            let measured = km / litres
            let plausible = kmplPlausible.contains(measured) && (accepted + dashUsed == 0 || abs(measured / estimate - 1) <= kmplOutlier)
            if plausible {
                estimate += (accepted == 0 ? kmplFirstWeight : kmplWeight) * (measured - estimate)
                accepted += 1
            }
            measures.append(TankMeasure(endRefuelId: r.id, endTimeMs: r.timeMs, km: km, litres: litres, used: plausible))
        } else if km > 0 && litres > 0 {
            measures.append(TankMeasure(endRefuelId: r.id, endTimeMs: r.timeMs, km: km, litres: litres, used: false))
        }
        lastFull = r
        litres = 0
    }
    takeDash(Int64.max)
    return Consumption(kmPerL: estimate, measures: measures, dashUsed: dashUsed)
}

// MARK: - Tank level and range

enum UnknownFuel { case noFullFill, away }

/// The range is rounded down to this.
let rangeStepKm = 5.0

struct FuelLevel: Equatable {
    /// Estimated litres in the tank now; nil when unknown.
    var litres: Double?
    var tankLitres: Double
    var kmPerL: Double
    /// Odometer km since the last fill-up (any kind); nil before the first one.
    var sinceRefuelKm: Double?
    var unknownBecause: UnknownFuel? = nil
    /// 12b: walked from a gauge reading, not from a full fill.
    var fromGauge = false

    /// 12b: the one number the rider sees: `rangeKm` rounded down to 5 km.
    var cautiousRangeKm: Int64? { rangeKm.map { Int64(($0 / rangeStepKm).rounded(.down) * rangeStepKm) } }
    var rangeKm: Double? { litres.map { $0 * kmPerL } }
    var fraction: Double? { litres.map { min(max($0 / tankLitres, 0), 1) } }
    /// Litres to fill up now.
    var toFillLitres: Double? { litres.map { max(tankLitres - $0, 0) } }
}

/// The tank now: full at the last full fill, minus odometer km ÷ `kmPerL` at each later step, plus each partial's
/// litres (capped at the tank), minus the km since to `odometerNowKm`. A gauge reading newer than the last full fill
/// is the starting point instead (12b).
func fuelLevel(tankLitres: Double, kmPerL: Double, refuels: [RefuelRecord], awayTimesMs: [Int64], odometerNowKm: Double?,
               marks: [FuelMark] = []) -> FuelLevel {
    let sorted = chronological(refuels)
    let since: Double? = (sorted.last.flatMap { last in odometerNowKm.map { max($0 - last.odometerKm, 0) } })
    let fullIndex = sorted.lastIndex { $0.full }
    let full = fullIndex.map { sorted[$0] }
    let newestMark = marks.max { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) }
    let mark = newestMark.flatMap { m in (full == nil || m.timeMs > full!.timeMs) ? m : nil }
    if full == nil && mark == nil {
        return FuelLevel(litres: nil, tankLitres: tankLitres, kmPerL: kmPerL, sinceRefuelKm: since, unknownBecause: .noFullFill)
    }
    let anchorMs = mark?.timeMs ?? full!.timeMs
    if awayTimesMs.contains(where: { $0 > anchorMs }) {
        return FuelLevel(litres: nil, tankLitres: tankLitres, kmPerL: kmPerL, sinceRefuelKm: since, unknownBecause: .away, fromGauge: mark != nil)
    }
    var litres = mark.map { min(max($0.litres, 0), tankLitres) } ?? tankLitres
    var odo = mark?.odometerKm ?? full!.odometerKm
    let after = mark.map { m in sorted.filter { $0.timeMs > m.timeMs } } ?? Array(sorted[(fullIndex! + 1)...])
    for r in after {
        litres = max(litres - max(r.odometerKm - odo, 0) / kmPerL, 0)
        litres = min(litres + r.litres, tankLitres)
        odo = max(odo, r.odometerKm)
    }
    if let now = odometerNowKm { litres = max(litres - max(now - odo, 0) / kmPerL, 0) }
    return FuelLevel(litres: litres, tankLitres: tankLitres, kmPerL: kmPerL, sinceRefuelKm: since, fromGauge: mark != nil)
}

// MARK: - Amounts, costs, spend

/// How the rider typed the amount at the pump.
enum AmountMode: String, CaseIterable { case rupiah = "RUPIAH", litres = "LITRES" }

/// Litres and Rp from one typed amount and the price per litre; nil if either is unusable.
func refuelAmounts(_ mode: AmountMode, amount: Double?, pricePerL: Double?) -> (litres: Double, rp: Double)? {
    guard let amount, amount > 0, let pricePerL, pricePerL > 0 else { return nil }
    switch mode {
    case .rupiah: return (amount / pricePerL, amount)
    case .litres: return (amount, amount * pricePerL)
    }
}

/// Rp per odometer km between the first and the last full fill (the fuel bought in between).
func costPerKm(_ refuels: [RefuelRecord]) -> Double? {
    let sorted = chronological(refuels)
    guard let first = sorted.firstIndex(where: { $0.full }), let last = sorted.lastIndex(where: { $0.full }), last > first else { return nil }
    let km = sorted[last].odometerKm - sorted[first].odometerKm
    if km <= 0 { return nil }
    return sorted[(first + 1)...last].reduce(0) { $0 + $1.totalRp } / km
}

struct Spend: Equatable { var last7Days: Double, thisMonth: Double, lastMonth: Double }

func spend(_ refuels: [RefuelRecord], nowMs: Int64, zone: TimeZone) -> Spend {
    let cal = calendar(zone)
    let monthStart = cal.date(from: cal.dateComponents([.year, .month], from: dateOf(nowMs)))!
    let lastMonthStart = cal.date(byAdding: .month, value: -1, to: monthStart)!
    func sum(_ from: Int64, _ to: Int64) -> Double { refuels.filter { $0.timeMs >= from && $0.timeMs < to }.reduce(0) { $0 + $1.totalRp } }
    let end = nowMs + 1
    return Spend(last7Days: sum(nowMs - 7 * 24 * 3_600_000, end), thisMonth: sum(msOf(monthStart), end),
                 lastMonth: sum(msOf(lastMonthStart), msOf(monthStart)))
}

/// "Rp 98.000": Indonesian grouping, whole rupiah.
func formatRupiah(_ rp: Double) -> String {
    "Rp " + groupedInt(Int64(rp.rounded(.toNearestOrAwayFromZero))).replacingOccurrences(of: ",", with: ".")
}

/// "5.0 L".
func formatLitres(_ l: Double) -> String { String(format: "%.1f L", l) }

/// "36.4 km/L".
func formatKmPerL(_ v: Double) -> String { String(format: "%.1f km/L", v) }

// MARK: - Refuel entry rules (13a)

/// An entry this much (L) over the estimated room in the tank is still "to the top".
let overflowToleranceL = 0.2

/// The litres field runs from 0 to the tank's size, never limited by the estimate.
func refuelLitresFit(_ litres: Double, tankLitres: Double) -> Bool { litres > 0 && litres <= tankLitres }

/// Whether a fill-up counts as a full-tank anchor: the rider's "Full refill" switch, or an entry that doesn't fit in
/// the room the estimate says is left.
func countsAsFull(filledToTop: Bool, litres: Double, spaceLeftL: Double?) -> Bool {
    filledToTop || (spaceLeftL.map { litres > $0 + overflowToleranceL } ?? false)
}

// MARK: - The Garage card's tank (11c)

let tankSegmentCount = 5
/// A boundary must be crossed by this much before the segment count changes (no flicker at a boundary).
let tankHysteresis = 0.02

/// Lit segments of the 5-segment tank for `fraction`, holding `previous` until a boundary is crossed by the hysteresis.
func tankSegments(_ fraction: Double, previous: Int?) -> Int {
    let f = min(max(fraction, 0), 1)
    let raw = min(max(Int((f * Double(tankSegmentCount) - 1e-9).rounded(.up)), 0), tankSegmentCount)
    guard let previous, previous != raw, abs(previous - raw) <= 1 else { return raw }
    if raw < previous {
        return f <= Double(previous - 1) / Double(tankSegmentCount) - tankHysteresis ? raw : previous
    }
    return f > Double(previous) / Double(tankSegmentCount) + tankHysteresis ? raw : previous
}
