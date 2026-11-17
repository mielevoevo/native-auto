import AVKit
import MediaPlayer
import SwiftUI

/// What iOS lets an app do with music (15e, decided in ios/CLAUDE.md): play / pause / skip and the track for **Apple
/// Music** (`systemMusicPlayer`), plus the system output picker and volume for whatever plays. Other apps (Spotify…)
/// can't be read or controlled by another app on iOS. Counterpart of Android's `MediaViewModel` (which follows any
/// app's media session through notification access).
@MainActor
final class MediaStore: ObservableObject {
    struct Track: Equatable {
        var title: String
        var artist: String?
        var artwork: UIImage?
        static func == (a: Track, b: Track) -> Bool { a.title == b.title && a.artist == b.artist && (a.artwork == nil) == (b.artwork == nil) }
    }

    @Published private(set) var track: Track?
    @Published private(set) var playing = false
    /// Apple Music's track details need media-library access; asked from the card, never at launch.
    @Published private(set) var access = MPMediaLibrary.authorizationStatus()

    private let player = MPMusicPlayerController.systemMusicPlayer
    private var observers: [NSObjectProtocol] = []

    init() {
        player.beginGeneratingPlaybackNotifications()
        let nc = NotificationCenter.default
        for name in [Notification.Name.MPMusicPlayerControllerPlaybackStateDidChange, .MPMusicPlayerControllerNowPlayingItemDidChange] {
            observers.append(nc.addObserver(forName: name, object: player, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.refresh() }
            })
        }
        observers.append(nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.access = MPMediaLibrary.authorizationStatus(); self?.refresh() }
        })
        refresh()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    func refresh() {
        playing = player.playbackState == .playing
        guard access == .authorized, let item = player.nowPlayingItem, let title = item.title, !title.isEmpty else {
            track = nil
            return
        }
        let art = item.artwork?.image(at: CGSize(width: 300, height: 300))
        track = Track(title: title, artist: item.artist, artwork: art)
    }

    func requestAccess() {
        MPMediaLibrary.requestAuthorization { status in
            Task { @MainActor [weak self] in self?.access = status; self?.refresh() }
        }
    }

    func playPause() {
        if player.playbackState == .playing { player.pause() } else { player.play() }
        playing.toggle()
    }

    func next() { player.skipToNextItem() }

    /// Like a car stereo: back to the start of the track, or the previous one in its first seconds.
    func previous() {
        if player.currentPlaybackTime > 3 { player.skipToBeginning() } else { player.skipToPreviousItem() }
    }
}

/// The media pane. Apple Music: art, title, artist, previous / play / next. Always: the output picker (AirPods, the
/// helmet's Bluetooth, the speaker) and, when there's room, the volume. Note that other apps can't be controlled.
struct MediaCard: View {
    let mode: CardMode
    @EnvironmentObject private var media: MediaStore
    @Environment(\.appColors) private var colors

    var body: some View {
        DashboardCard(pane: .media, mode: mode) {
            Group {
                switch mode {
                case .mini: mini
                case .expanded, .fullscreen: big
                default: small
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var title: String { media.track?.title ?? (media.playing ? "Apple Music" : "Nothing playing") }
    private var subtitle: String { media.track?.artist ?? "Apple Music" }

    // MARK: Sizes

    private var mini: some View {
        HStack(spacing: 12) {
            Art(image: media.track?.artwork, size: 44)
            Text(title).font(.geist(17, .medium)).foregroundStyle(colors.text).lineLimit(1)
                .accessibilityIdentifier("media_title")
            Spacer(minLength: 4)
            playButton(52)
        }
        .padding(.horizontal, 14)
    }

    private var small: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Art(image: media.track?.artwork, size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.geist(18, .semibold)).foregroundStyle(colors.text).lineLimit(1)
                        .accessibilityIdentifier("media_title")
                    Text(subtitle).font(.geist(14)).foregroundStyle(colors.textDim).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 14) {
                skipButton(back: true, 52)
                playButton(60)
                skipButton(back: false, 52)
                OutputPicker().frame(width: 52, height: 52)
            }
        }
        .padding(.horizontal, 14).padding(.bottom, 10)
    }

    private var big: some View {
        VStack(spacing: 14) {
            HStack(spacing: 16) {
                Art(image: media.track?.artwork, size: mode == .fullscreen ? 150 : 88)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.geist(24, .semibold)).foregroundStyle(colors.text).lineLimit(2).minimumScaleFactor(0.7)
                        .accessibilityIdentifier("media_title")
                    Text(subtitle).font(.geist(16)).foregroundStyle(colors.textDim).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 20) {
                skipButton(back: true, 60)
                playButton(68)
                skipButton(back: false, 60)
                OutputPicker().frame(width: 56, height: 56)
            }
            VolumeSlider().frame(height: 30).padding(.horizontal, 6)
            // One line under the controls: the access button until it's answered, then the note.
            if media.access == .notDetermined {
                QuietButton(title: "Show Apple Music's track") { media.requestAccess() }.accessibilityIdentifier("media_access")
            } else {
                Text("Other apps (Spotify…) can't be controlled on iOS; the output picker works for all.")
                    .font(.geist(13)).foregroundStyle(colors.textDim).multilineTextAlignment(.center)
                    .lineLimit(2).minimumScaleFactor(0.8)
                    .accessibilityIdentifier("media_note")
            }
        }
        .padding(.horizontal, 18).padding(.bottom, 12)
    }

    // MARK: Buttons (gloves: 52 pt and up)

    private func playButton(_ size: CGFloat) -> some View {
        Button { Haptics.tap(); media.playPause() } label: {
            Image(systemName: media.playing ? "pause.fill" : "play.fill").font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(colors.onButton)
                .frame(width: size, height: size).background(colors.button, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(media.playing ? "Pause" : "Play")
        .accessibilityIdentifier("media_play")
    }

    private func skipButton(back: Bool, _ size: CGFloat) -> some View {
        Button { Haptics.tap(); back ? media.previous() : media.next() } label: {
            Image(systemName: back ? "backward.fill" : "forward.fill").font(.system(size: size * 0.36, weight: .semibold))
                .foregroundStyle(colors.onButtonQuiet)
                .frame(width: size, height: size).background(colors.buttonQuiet, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(back ? "Previous track" : "Next track")
        .accessibilityIdentifier(back ? "media_previous" : "media_next")
    }
}

private struct Art: View {
    let image: UIImage?
    let size: CGFloat
    @Environment(\.appColors) private var colors

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: size * 0.38)).foregroundStyle(colors.textDim)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(colors.buttonQuiet)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.16))
        .accessibilityHidden(true)
    }
}

/// The system's audio output picker (AirPlay / Bluetooth / speaker), drawn in the theme's colours.
struct OutputPicker: UIViewRepresentable {
    @Environment(\.appColors) private var colors

    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.prioritizesVideoDevices = false
        v.accessibilityIdentifier = "media_output"
        return v
    }

    func updateUIView(_ v: AVRoutePickerView, context: Context) {
        v.tintColor = UIColor(colors.textDim)
        v.activeTintColor = UIColor(colors.accent)
    }
}

/// The system volume slider (an app can't set the volume itself). Hidden by iOS in the simulator.
struct VolumeSlider: UIViewRepresentable {
    @Environment(\.appColors) private var colors

    func makeUIView(context: Context) -> MPVolumeView {
        let v = MPVolumeView(frame: .zero)
        v.accessibilityIdentifier = "media_volume"
        return v
    }

    func updateUIView(_ v: MPVolumeView, context: Context) {
        v.tintColor = UIColor(colors.accent)
    }
}
