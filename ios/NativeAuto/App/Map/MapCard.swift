import SwiftUI

/// The map pane (port of Android `MapPane`): MapLibre + OpenFreeMap (free OSM tiles, no key), the rider's dot, the
/// route, and navigation on top. Big (Expanded / Fullscreen) maps carry the banner and the ETA card; a small map
/// while guiding shows one chip, with mute and End in its header (13b). When the Navigation pane is on screen the map
/// leaves the banner, cards and chip to it.
struct MapCard: View {
    let mode: CardMode
    @EnvironmentObject private var navigator: Navigator
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var trip: TripStore
    @EnvironmentObject private var model: DashboardModel
    @EnvironmentObject private var map: MapStore
    @Environment(\.appColors) private var colors
    @Environment(\.paneOnScreen) private var onScreen

    var body: some View {
        let nav = navigator.state
        let big = mode == .expanded || mode == .fullscreen
        let look = MapLook.of(dark: colors.isDark, route: UIColor(colors.accent))
        DashboardCard(pane: .map, mode: mode, headerActions: headerActions(nav, big: big)) {
            ZStack {
                Color(look.background)
                if !map.asleep {
                    DashboardMapView(
                        look: look,
                        fix: nav.guidance?.position ?? trip.fix,
                        route: nav.routeGeometry,
                        destination: nav.destination?.point,
                        frameRoute: { if case .preview = nav { true } else { false } }(),
                        bottomClearance: showPanels ? 150 : 0,
                        pinEnabled: mode == .fullscreen,
                        onPin: { navigator.routeToPin($0) },
                        store: map
                    )
                    .transition(.opacity.animation(.easeIn(duration: 0.35)))
                } else {
                    Color(look.background).accessibilityIdentifier("map_asleep")
                }
                if onScreen { overlays(nav, big: big) }
            }
            .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: CardStyle.radius, bottomTrailingRadius: CardStyle.radius))
        }
        .onChange(of: model.layout.isOnScreen(.map) && !model.screenOver, initial: true) { _, on in map.setOnScreen(on) }
    }

    /// The map leaves navigation panels to the Navigation pane when that is on screen.
    private var showPanels: Bool { !model.layout.isOnScreen(.nav) }

    private func headerActions(_ nav: NavState, big: Bool) -> AnyView? {
        guard nav.isNavigating, !big else { return nil }
        let slim = mode == .mini || mode == .narrow
        return AnyView(HStack(spacing: 2) {
            if !slim { VoiceButton() }
            EndNavButton(style: .header)
        })
    }

    @ViewBuilder private func overlays(_ nav: NavState, big: Bool) -> some View {
        VStack(spacing: 10) {
            HStack(alignment: .top) {
                if showPanels, let g = nav.guidance {
                    if big { NavigationBanner(g: g, unit: settings.speedUnit).frame(maxWidth: 560) } else { NavigationChip(g: g, unit: settings.speedUnit) }
                }
                Spacer(minLength: 0)
            }
            Spacer(minLength: 0)
            if showPanels { bottomCard(nav) }
            HStack(alignment: .bottom) {
                if showPanels, big, let g = nav.guidance { NavigationSummary(g: g, unit: settings.speedUnit) }
                Spacer(minLength: 0)
                VStack(spacing: 12) {
                    if big, nav.isNavigating {
                        // As Android's `MapButtons` while guiding on a big map: Mute and End above Recenter.
                        Fab(icon: settings.voiceGuidance ? "speaker.wave.2.fill" : "speaker.slash.fill",
                            label: settings.voiceGuidance ? "Mute voice guidance" : "Unmute voice guidance", on: settings.voiceGuidance) {
                            Haptics.toggle(); settings.voiceGuidance.toggle()
                        }
                        .accessibilityIdentifier("nav_voice")
                        EndNavButton(style: .fab)
                    }
                    if big, case .idle = nav {
                        Fab(icon: "magnifyingglass", label: "Navigate to…") { model.showSearch = true }
                            .accessibilityIdentifier("map_search")
                    }
                    Fab(icon: map.following ? "location.north.line.fill" : "location.north.line", label: "Recenter map", on: map.following) {
                        Haptics.tap(); map.recenter()
                    }
                    .accessibilityIdentifier("map_recenter")
                }
            }
        }
        .padding(10)
    }

    @ViewBuilder private func bottomCard(_ nav: NavState) -> some View {
        switch nav {
        case .routing, .failed, .arrived:
            NavStatusCard(state: nav, onRetry: navigator.retry, onDismiss: navigator.stop).frame(maxWidth: 560)
        case .preview(let place, let plan):
            RoutePreviewCard(destination: place, plan: plan, unit: settings.speedUnit, onStart: navigator.start, onCancel: navigator.stop)
                .frame(maxWidth: 560)
        default:
            EmptyView()
        }
    }
}

/// A 56 pt round map button (gloves). `on` = navy with the accent, like Android's follow button.
struct Fab: View {
    let icon: String
    let label: String
    var on = false
    let action: () -> Void
    @Environment(\.appColors) private var colors

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(on ? colors.accent : colors.text)
                .frame(width: CardStyle.bigButton, height: CardStyle.bigButton)
                .background(on ? colors.navy : colors.surface, in: Circle())
                .overlay(Circle().strokeBorder(colors.outline, lineWidth: 1))
                .shadow(color: .black.opacity(0.3), radius: 4, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
