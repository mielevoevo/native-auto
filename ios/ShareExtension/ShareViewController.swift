import UIKit
import UniformTypeIdentifiers

/// "Native Auto" in the share sheet (15e). Takes the shared text and link (a place from Google Maps, any Maps link,
/// or plain text to search), opens the app with `nativeauto://open?text=…`, and closes. iOS has no supported way for a
/// share extension to open its app, so the open goes through `UIApplication` found up the responder chain; if iOS
/// refuses it, the link is copied and the rider is told to paste it in Native Auto's search.
final class ShareViewController: UIViewController {
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        label.textColor = .white
        label.font = .systemFont(ofSize: 20, weight: .semibold)
        label.numberOfLines = 0
        label.textAlignment = .center
        label.text = "Opening Native Auto…"
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { @MainActor in await handOver() }
    }

    private func handOver() async {
        var texts: [String] = []
        var urls: [URL] = []
        for item in extensionContext?.inputItems as? [NSExtensionItem] ?? [] {
            if let t = item.attributedContentText?.string { texts.append(t) }
            for p in item.attachments ?? [] {
                if p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let u = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                    urls.append(u)
                } else if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                          let t = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                    texts.append(t)
                }
            }
        }
        let text = ShareLink.combine(texts: texts, urls: urls)
        guard let url = ShareLink.openURL(for: text) else {
            finish(message: "Nothing to open: share a place or a link.")
            return
        }
        if openApp(url) {
            extensionContext?.completeRequest(returningItems: nil)
        } else {
            UIPasteboard.general.string = text
            finish(message: "Copied. Open Native Auto and paste it in search (Where to?).")
        }
    }

    /// `UIApplication.open(_:options:completionHandler:)` on the host app's application object (not callable directly
    /// from an extension).
    private func openApp(_ url: URL) -> Bool {
        let sel = NSSelectorFromString("openURL:options:completionHandler:")
        var r: UIResponder? = self
        while let next = r {
            if next.isKind(of: NSClassFromString("UIApplication")!), next.responds(to: sel) {
                typealias Open = @convention(c) (AnyObject, Selector, URL, [String: Any], ((Bool) -> Void)?) -> Void
                let f = unsafeBitCast(next.method(for: sel), to: Open.self)
                f(next, sel, url, [:], nil)
                return true
            }
            r = next.next
        }
        return false
    }

    private func finish(message: String) {
        label.text = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
