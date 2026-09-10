import SwiftUI

struct ChainExportView: View {
    let chainName: String
    let hops: [HopNodeProfile]
    var mode: HopperExportMode = .file
    @Environment(\.dismiss) private var dismiss

    @State private var password = ""
    @State private var errorMessage: String?

    private var payload: HopperConf.Payload {
        .chain(name: chainName, hops: hops)
    }

    private var qrJSON: String {
        (try? HopperConf.qrPayloadJSON(for: payload)) ?? ""
    }

    var body: some View {
        NavigationStack {
            Group {
                if mode == .qr {
                    VStack(spacing: 16) {
                        Text("Scan on another device to import this chain and its servers. The QR is only for in-person transfer.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        if hops.isEmpty {
                            Text("Add servers to this chain before exporting.")
                                .foregroundStyle(.secondary)
                        } else if let qr = HopQRCodeImage.make(from: qrJSON) {
                            HopFitQRCode(image: qr)
                        } else {
                            Text("Could not generate QR code (payload may be too large). Export as a file instead.")
                                .foregroundStyle(.orange)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    Form {
                        Section {
                            if hops.isEmpty {
                                Text("Add servers to this chain before exporting.")
                                    .foregroundStyle(.secondary)
                            } else {
                                SecureField("Optional encryption password", text: $password)
                                Text("Leave empty to use the default password. The file always encrypts private keys.")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                Button("Share .hopperconf…") {
                                    shareFile()
                                }
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
            let data = try HopperConf.encryptFile(payload: payload, password: password)
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(HopperConf.suggestedFileName(for: payload))
            try data.write(to: url, options: .atomic)
            HopperConfSharePresenter.present(fileURL: url) {
                try? FileManager.default.removeItem(at: url)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
