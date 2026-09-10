import SwiftUI

/// Import encrypted `.hopperconf` or legacy plaintext hop JSON (deploy / QR).
struct HopImportView: View {
    @Environment(\.dismiss) private var dismiss
    let onImport: (HopperConf.Payload) -> Void

    @State private var mode: Mode
    @State private var jsonText = ""
    @State private var password = ""
    @State private var errorMessage: String?
    @State private var showPicker = false
    @State private var pendingFileData: Data?

    enum Mode: String, CaseIterable, Identifiable {
        case file = "File"
        case paste = "Paste JSON"
        var id: String { rawValue }
    }

    init(mode: Mode = .file, onImport: @escaping (HopperConf.Payload) -> Void) {
        self.onImport = onImport
        _mode = State(initialValue: mode)
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("Source", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)

                if mode == .file {
                    Section {
                        Button("Choose .hopperconf…") { showPicker = true }
                        SecureField("Password (optional)", text: $password)
                        Text("Tried automatically with the default password first; enter a custom password only if needed.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        if pendingFileData != nil {
                            Button("Import file") { importPendingFile() }
                        }
                    } footer: {
                        Text("Opens encrypted Hopper share files. You can also open a .hopperconf from Files or another app.")
                    }
                } else {
                    Section {
                        Text("Paste hop config JSON from deploy.sh or a scanned QR payload.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        TextEditor(text: $jsonText)
                            .font(.system(.caption, design: .monospaced))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .frame(minHeight: 160)
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Import")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if mode == .paste {
                        Button("Import") { importPaste() }
                            .disabled(jsonText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .sheet(isPresented: $showPicker) {
                HopperConfDocumentPicker(
                    onPick: { url in
                        showPicker = false
                        loadFile(url)
                    },
                    onCancel: { showPicker = false }
                )
            }
        }
    }

    private func loadFile(_ url: URL) {
        errorMessage = nil
        do {
            let data = try Data(contentsOf: url)
            pendingFileData = data
            if HopperConf.isHopperConfFile(data) {
                // Wait for optional password + Import.
            } else if let text = String(data: data, encoding: .utf8) {
                // Plain JSON file — import immediately.
                let payload = try HopperConf.parsePayloadJSON(text)
                onImport(payload)
                dismiss()
            } else {
                throw HopperConf.ConfError.invalidEnvelope
            }
        } catch {
            errorMessage = error.localizedDescription
            pendingFileData = nil
        }
    }

    private func importPendingFile() {
        errorMessage = nil
        guard let data = pendingFileData else { return }
        do {
            let payload: HopperConf.Payload
            if HopperConf.isHopperConfFile(data) {
                payload = try HopperConf.decryptFile(data, password: password)
            } else if let text = String(data: data, encoding: .utf8) {
                payload = try HopperConf.parsePayloadJSON(text)
            } else {
                throw HopperConf.ConfError.invalidEnvelope
            }
            onImport(payload)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func importPaste() {
        errorMessage = nil
        do {
            let payload = try HopperConf.parsePayloadJSON(jsonText)
            onImport(payload)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Password prompt when opening a `.hopperconf` from outside the app.
struct HopperConfOpenPasswordView: View {
    let fileData: Data
    let onImport: (HopperConf.Payload) -> Void
    let onCancel: () -> Void

    @State private var password = ""
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Tried automatically with the default password first; enter a custom password only if that fails.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    SecureField("Password (optional)", text: $password)
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Import .hopperconf")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import") { importFile() }
                }
            }
        }
    }

    private func importFile() {
        errorMessage = nil
        do {
            let payload = try HopperConf.decryptFile(fileData, password: password)
            onImport(payload)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
