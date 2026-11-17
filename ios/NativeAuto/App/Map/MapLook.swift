import MapLibre
import UIKit

/// How the map looks in each theme (port of Android `MapLook`, 8c). Dark: OpenFreeMap's dark style recoloured to true
/// black (OLED), roads the lightest thing on it. Light: OpenFreeMap's Positron as it is. The route is drawn in the
/// theme's accent (13c: the route follows the accent).
struct MapLook: Equatable {
    var dark: Bool
    var styleURL: URL
    /// Land colour, and behind the map until it draws.
    var background: UIColor
    var route: UIColor
    var routeCasing: UIColor
    var destination: UIColor

    /// MapLibre's zoom is one level below Google's (512 px tiles); `FollowCamera` speaks Google levels.
    static let zoomOffset: Double = 1
    /// The map dot stays cyan whatever the accent (13c).
    static let vehicle = UIColor(hex: 0x3FC8E0)

    static func of(dark: Bool, route: UIColor) -> MapLook {
        dark
            ? MapLook(dark: true, styleURL: URL(string: "https://tiles.openfreemap.org/styles/dark")!, background: .black,
                      route: route, routeCasing: UIColor(hex: 0x0F2A4A), destination: UIColor(hex: 0xE5484D))
            : MapLook(dark: false, styleURL: URL(string: "https://tiles.openfreemap.org/styles/positron")!, background: UIColor(hex: 0xF2F3F0),
                      route: route, routeCasing: .white, destination: UIColor(hex: 0xC62828))
    }

    /// Recolours the loaded style for the dashboard. Nothing to do for Positron.
    func apply(to style: MLNStyle) {
        guard dark else { return }
        // OpenFreeMap's dark style draws roads darker than the land; from a handlebar mount, roads must stand out.
        // Layers missing from a future style version are skipped. Android `applyOledPalette`; Google twin
        // res/raw/map_style_dark.json.
        func fill(_ id: String, _ c: UInt32, outline: UInt32? = nil) {
            guard let l = style.layer(withIdentifier: id) as? MLNFillStyleLayer else { return }
            l.fillColor = NSExpression(forConstantValue: UIColor(hex: c))
            if let outline { l.fillOutlineColor = NSExpression(forConstantValue: UIColor(hex: outline)) }
        }
        func line(_ id: String, _ c: UInt32) {
            (style.layer(withIdentifier: id) as? MLNLineStyleLayer)?.lineColor = NSExpression(forConstantValue: UIColor(hex: c))
        }
        func text(_ id: String, _ c: UInt32) {
            guard let l = style.layer(withIdentifier: id) as? MLNSymbolStyleLayer else { return }
            l.textColor = NSExpression(forConstantValue: UIColor(hex: c))
            l.textHaloColor = NSExpression(forConstantValue: UIColor.black)
        }
        (style.layer(withIdentifier: "background") as? MLNBackgroundStyleLayer)?.backgroundColor = NSExpression(forConstantValue: background)
        fill("landuse_residential", 0x06080B)
        fill("landcover_wood", 0x06120A); fill("landuse_park", 0x06120A)
        fill("building", 0x0E1116, outline: 0x06080B)
        fill("water", 0x03101B); line("waterway", 0x03101B)
        line("highway_path", 0x1C2027)
        line("highway_minor", 0x2A2F37)
        line("highway_major_casing", 0x000000); line("highway_major_inner", 0x3A414B); line("highway_major_subtle", 0x3A414B)
        line("highway_motorway_casing", 0x14171C); line("highway_motorway_inner", 0x4C5662); line("highway_motorway_subtle", 0x4C5662)
        text("highway_name_other", 0x8A9099); text("highway_name_motorway", 0xE8EAED)
        text("water_name", 0x4E6D82)
        for id in ["place_other", "place_suburb", "place_village"] { text(id, 0x9AA0A6) }
        for id in ["place_town", "place_city", "place_city_large"] { text(id, 0xC4C8CD) }
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
