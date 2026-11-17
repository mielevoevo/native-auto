import SwiftUI
import UniformTypeIdentifiers

/// "Navigate to…" (port of Android `DestinationSearchSheet`): recent places first, then Photon results once typing
/// pauses (fair use: never a request per key). A pasted Google Maps link is opened as a link instead of searched.
struct DestinationSearchSheet: View {
    @EnvironmentObject private var navigator: Navigator
    @EnvironmentObject private var settings: SettingsStore
    @Environment(\.appColors) private var colors
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool
    @State private var query = ""
    @State private var results: [Place] = []
    @State private var searching = false
    @State private var message: String?

    /// The pause in typing before a search goes out.
    static let debounceMs: UInt64 = 600

    var body: some View {
        SheetScaffold(title: "Navigate to…") {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").foregroundStyle(colors.textDim)
                    TextField("Place, address or Maps link", text: $query)
                        .font(.geist(18)).foregroundStyle(colors.text)
                        .focused($focused)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .onSubmit { Task { await run(query) } }
                        .accessibilityIdentifier("search_field")
                    if !query.isEmpty {
                        Button { query = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(colors.textDim).frame(width: 44, height: 44)
                        }
                        .buttonStyle(.plain).accessibilityLabel("Clear")
                    }
                }
                .padding(.leading, 14).frame(minHeight: 56)
                .background(colors.buttonQuiet, in: RoundedRectangle(cornerRadius: 14))

                // A link copied from Google Maps: paste it here (iOS has no share target for it until the share extension).
                PasteButton(payloadType: String.self) { strings in
                    guard let text = strings.first else { return }
                    Task { @MainActor in open(text) }
                }
                .tint(colors.buttonQuiet)
                .labelStyle(.titleAndIcon)
                .accessibilityIdentifier("search_paste")

                if searching {
                    HStack(spacing: 10) { ProgressView().tint(colors.accent); Text("Searching…").foregroundStyle(colors.textDim) }
                } else if let message {
                    Text(message).font(.geist(15)).foregroundStyle(colors.textDim)
                }

                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    if settings.recentPlaces.isEmpty {
                        Text("Type a place or address, or long-press the map in fullscreen to drop a pin.")
                            .font(.geist(15)).foregroundStyle(colors.textDim)
                    } else {
                        Text("RECENT").font(.geist(13, .semibold)).foregroundStyle(colors.textDim)
                        list(settings.recentPlaces, prefix: "recent")
                    }
                } else {
                    list(results, prefix: "result")
                }
            }
        }
        .onAppear { focused = true }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            message = nil
            guard !q.isEmpty else { results = []; searching = false; return }
            try? await Task.sleep(nanoseconds: Self.debounceMs * 1_000_000)
            guard !Task.isCancelled else { return }
            await run(q)
        }
    }

    private func list(_ places: [Place], prefix: String) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(places.enumerated()), id: \.offset) { i, p in
                Button { pick(p) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.name).font(.geist(17, .medium)).foregroundStyle(colors.text).lineLimit(1)
                        if let d = p.detail { Text(d).font(.geist(14)).foregroundStyle(colors.textDim).lineLimit(1) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("\(prefix)_\(i)")
                Divider().overlay(colors.outline)
            }
        }
    }

    private func run(_ text: String) async {
        let q = text.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        if findMapsUrl(q) != nil { open(q); return }
        searching = true
        defer { searching = false }
        do {
            let found = try await navigator.search.search(q, near: navigator.lastPoint)
            guard q == query.trimmingCharacters(in: .whitespaces) else { return } // typing went on
            results = found
            message = found.isEmpty ? "Nothing found for \"\(q)\"" : nil
        } catch is CancellationError {
        } catch {
            message = "Search needs a connection. Try again in a moment."
        }
    }

    private func pick(_ place: Place) {
        Haptics.tap()
        navigator.routeTo(place)
        dismiss()
    }

    private func open(_ text: String) {
        navigator.openLink(text)
        dismiss()
    }
}
