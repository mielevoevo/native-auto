import SwiftUI

/// The layouts button: big thumbnails of the presets for the current orientation, drawn from `computeRects`.
struct LayoutSwitcher: View {
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let l = model.layout
        let presets = LayoutPreset.allCases.filter { $0.portrait == l.portrait }
        SheetScaffold(title: "Layouts") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: l.portrait ? 140 : 180), spacing: 14)], spacing: 14) {
                ForEach(presets, id: \.self) { p in
                    let on = p == l.preset && l.override == nil
                    Button {
                        Haptics.tick()
                        model.change { $0.setPreset(p) }
                        dismiss()
                    } label: {
                        VStack(spacing: 8) {
                            Thumbnail(view: LayoutView(preset: p, panes: l.assignments[p]!), bigOnLeft: l.bigOnLeft, split: l.split)
                                .aspectRatio(l.portrait ? 9.0 / 16.0 : 16.0 / 9.0, contentMode: .fit)
                                .frame(maxHeight: l.portrait ? 200 : 110)
                            Text(p.label).font(.geist(15, .medium)).foregroundStyle(on ? colors.onNavy : colors.text)
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity)
                        .background(on ? colors.navy : colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("layout_\(p.rawValue)")
                }
            }
        }
    }
}

private struct Thumbnail: View {
    let view: LayoutView
    let bigOnLeft: Bool
    let split: Double
    @Environment(\.appColors) private var colors

    var body: some View {
        GeometryReader { g in
            let rects = computeRects(view, bigOnLeft: bigOnLeft, split: split)
            ForEach(view.panes, id: \.self) { p in
                let r = rects[p]!
                RoundedRectangle(cornerRadius: 5)
                    .fill(colors.card)
                    .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(colors.outline))
                    .overlay(Text(p.title).font(.geist(10, .medium)).foregroundStyle(colors.textDim).lineLimit(1).minimumScaleFactor(0.6).padding(2))
                    .frame(width: max(0, r.width * g.size.width - 3), height: max(0, r.height * g.size.height - 3))
                    .offset(x: r.left * g.size.width + 1.5, y: r.top * g.size.height + 1.5)
            }
        }
    }
}

/// Hold a card still after it lifts, then let go: choose what the slot shows. A pane already shown trades places;
/// any other replaces the slot's pane. Fullscreen and Maximize live here too, for cards whose header has no buttons.
struct PanePicker: View {
    let slot: Pane
    @EnvironmentObject private var model: DashboardModel
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let l = model.layout
        SheetScaffold(title: "Choose what this pane shows") {
            VStack(spacing: 16) {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                    ForEach(Pane.pickable, id: \.self) { p in
                        let current = p == slot
                        let shown = l.view.panes.contains(p) && !current
                        Button {
                            Haptics.tick()
                            if !current { model.change { $0.assign(slot, p) } }
                            dismiss()
                        } label: {
                            HStack {
                                Text(p.title).font(.geist(17, .medium))
                                Spacer()
                                if shown { Text("trade places").font(.geist(13)).foregroundStyle(colors.textDim) }
                                if current { Image(systemName: "checkmark") }
                            }
                            .foregroundStyle(current ? colors.onNavy : colors.text)
                            .padding(.horizontal, 16)
                            .frame(minHeight: CardStyle.bigButton)
                            .background(current ? colors.navy : colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("pick_\(p.rawValue)")
                    }
                }
                HStack(spacing: 12) {
                    BigButton(title: l.fullscreen == slot ? "Exit fullscreen" : "Fullscreen", icon: "arrow.up.left.and.arrow.down.right") {
                        model.change { $0.toggleFullscreen(slot) }; dismiss()
                    }
                    if l.canMaximize(slot) {
                        BigButton(title: l.maximized == slot ? "Restore" : "Maximize", icon: "rectangle.expand.vertical") {
                            model.change { $0.toggleMaximized(slot) }; dismiss()
                        }
                    }
                }
            }
        }
    }
}

/// A sheet in the app's colours: a title row with Done, and scrolling content.
struct SheetScaffold<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title).font(.geist(20, .semibold)).foregroundStyle(colors.text)
                Spacer()
                Button("Done") { dismiss() }
                    .font(.geist(17, .semibold)).foregroundStyle(colors.accent)
                    .frame(minWidth: 56, minHeight: 48)
            }
            .padding(.horizontal, 20).padding(.top, 12)
            ScrollView { content().padding(20) }
        }
        .background(colors.surface.ignoresSafeArea())
    }
}
