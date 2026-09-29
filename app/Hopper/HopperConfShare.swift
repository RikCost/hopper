import SwiftUI
import UniformTypeIdentifiers
import UIKit

enum HopperExportMode: String, Identifiable {
    case qr
    case file

    var id: String { rawValue }

    var navigationTitle: String {
        switch self {
        case .qr: return "QR code"
        case .file: return "Export as file"
        }
    }
}

enum HopperImportMode: String, Identifiable {
    case file
    case paste
    case remote

    var id: String { rawValue }
}

struct HopperImportButtons: View {
    var enabled: Bool = true
    var onAddManually: (() -> Void)? = nil
    let onSelect: (HopperImportMode) -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                importButton("Import from file", mode: .file)
                importButton("Import from copy&paste", mode: .paste)
            }
            if let onAddManually {
                HStack(spacing: 12) {
                    Button("Import remotely") { onSelect(.remote) }
                        .buttonStyle(.bordered)
                        .disabled(!enabled)
                        .frame(maxWidth: .infinity)
                    Button("Add manually") { onAddManually() }
                        .buttonStyle(.bordered)
                        .disabled(!enabled)
                        .frame(maxWidth: .infinity)
                }
            } else {
                Button("Import remotely") { onSelect(.remote) }
                    .buttonStyle(.bordered)
                    .disabled(!enabled)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func importButton(_ title: String, mode: HopperImportMode) -> some View {
        Button(title) { onSelect(mode) }
            .buttonStyle(.bordered)
            .disabled(!enabled)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity)
    }
}

struct HopperExportButtonsRow: View {
    var enabled: Bool = true
    let onSelect: (HopperExportMode) -> Void

    var body: some View {
        HStack(spacing: 12) {
            modeButton("Export as file…", mode: .file)
            modeButton("Show QR code…", mode: .qr)
        }
    }

    private func modeButton(_ title: String, mode: HopperExportMode) -> some View {
        Button(title) { onSelect(mode) }
            .buttonStyle(.bordered)
            .disabled(!enabled)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity)
    }
}

extension UTType {
    static var hopperConf: UTType {
        UTType(filenameExtension: HopperConf.fileExtension) ?? UTType(exportedAs: HopperConf.uti)
    }
}

enum HopperConfSharePresenter {
    /// Presents the system share sheet from the topmost view controller.
    /// Avoids nested SwiftUI `.sheet` + `UIActivityViewController`, which often shows blank on iPhone.
    @MainActor
    static func present(fileURL: URL, onComplete: (() -> Void)? = nil) {
        guard let presenter = topViewController() else {
            onComplete?()
            return
        }

        let controller = UIActivityViewController(activityItems: [fileURL], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in
            Task { @MainActor in
                onComplete?()
            }
        }

        if let popover = controller.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(
                x: presenter.view.bounds.midX,
                y: presenter.view.bounds.midY,
                width: 1,
                height: 1
            )
            popover.permittedArrowDirections = []
        }

        presenter.present(controller, animated: true)
    }

    @MainActor
    private static func topViewController(base: UIViewController? = nil) -> UIViewController? {
        let root: UIViewController?
        if let base {
            root = base
        } else {
            root = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }?
                .rootViewController
        }

        if let nav = root as? UINavigationController {
            return topViewController(base: nav.visibleViewController)
        }
        if let tab = root as? UITabBarController {
            return topViewController(base: tab.selectedViewController)
        }
        if let presented = root?.presentedViewController {
            return topViewController(base: presented)
        }
        return root
    }
}

/// Document picker for `.hopperconf` (and generic files that may be hopperconf).
struct HopperConfDocumentPicker: UIViewControllerRepresentable {
    var onPick: (URL) -> Void
    var onCancel: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.hopperConf, .json, .data], asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        let onCancel: (() -> Void)?

        init(onPick: @escaping (URL) -> Void, onCancel: (() -> Void)?) {
            self.onPick = onPick
            self.onCancel = onCancel
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else {
                onCancel?()
                return
            }
            onPick(url)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onCancel?()
        }
    }
}
