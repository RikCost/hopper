import CoreImage.CIFilterBuiltins
import SwiftUI

struct ServerExportView: View {
    let server: HopNodeProfile
    var mode: HopperExportMode = .file
    @Environment(\.dismiss) private var dismiss

    @State private var password = ""
    @State private var errorMessage: String?

    private var qrJSON: String {
        (try? HopperConf.qrPayloadJSON(for: .server(server))) ?? "{}"
    }

    var body: some View {
        NavigationStack {
            Group {
                if mode == .qr {
                    VStack(spacing: 16) {
                        Text("Scan on another device to import this server. The QR is only for in-person transfer — it is not shared as a file.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        if let qr = HopQRCodeImage.make(from: qrJSON) {
                            HopFitQRCode(image: qr)
                        } else {
                            Text("Could not generate QR code.")
                                .foregroundStyle(.red)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Form {
                        Section {
                            SecureField("Optional encryption password", text: $password)
                            Text("Leave empty to use the default password. The file always encrypts private keys.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Button("Share .hopperconf…") {
                                shareFile()
                            }
                        } header: {
                            Text("Share file")
                        }
                        if let errorMessage {
                            Section {
                                Text(errorMessage).foregroundStyle(.red)
                            }
                        }
                    }
                }
            }
            .navigationTitle(mode.navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func shareFile() {
        errorMessage = nil
        do {
            let data = try HopperConf.encryptFile(payload: .server(server), password: password)
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(HopperConf.suggestedFileName(for: .server(server)))
            try data.write(to: url, options: .atomic)
            HopperConfSharePresenter.present(fileURL: url) {
                try? FileManager.default.removeItem(at: url)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

enum HopQRCodeImage {
    static func make(from string: String, scale: CGFloat = 12) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

struct HopFitQRCode: View {
    let image: UIImage

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(8)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .frame(width: side, height: side)
                .frame(width: geo.size.width, height: geo.size.height, alignment: .center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
