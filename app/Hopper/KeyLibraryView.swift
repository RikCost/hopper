import SwiftUI
import UIKit

struct KeyLibraryView: View {
    @EnvironmentObject private var vpn: VPNController

    @State private var showGenerate = false
    @State private var showPaste = false
    @State private var importMode: HopperImportMode?
    @State private var showScanner = false
    @State private var blockedDeleteKey: DeploySSHKey?

    var body: some View {
        List {
            Section {
                HopperImportButtons { importMode = $0 }
            }
            if vpn.state.deployKeys.isEmpty {
                ContentUnavailableView(
                    "No keys",
                    systemImage: "key",
                    description: Text("Generate an ED25519 key, paste a private key, or import a .hopperconf file.")
                )
            } else {
                ForEach(vpn.state.deployKeys) { key in
                    NavigationLink {
                        KeyDetailView(keyID: key.id)
                    } label: {
                        keyLabel(key)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            if key.canDelete(servers: vpn.state.servers) {
                                vpn.deleteDeployKey(id: key.id)
                            } else {
                                blockedDeleteKey = key
                            }
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
        }
        .navigationTitle("Keys")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button("Generate") { showGenerate = true }
                Button("Paste") { showPaste = true }
                Button("Scan QR") { showScanner = true }
            }
        }
        .sheet(isPresented: $showGenerate) {
            KeyGenerateView()
        }
        .sheet(isPresented: $showPaste) {
            KeyPasteView()
        }
        .sheet(item: $importMode) { mode in
            HopperImportSheet(mode: mode) { importMode = nil }
        }
        .sheet(isPresented: $showScanner) {
            QRCodeScannerView { payload in
                if vpn.handleScannedQR(payload) {
                    showScanner = false
                }
            }
        }
        .alert("Can't delete key", isPresented: Binding(
            get: { blockedDeleteKey != nil },
            set: { if !$0 { blockedDeleteKey = nil } }
        ), presenting: blockedDeleteKey) { _ in
            Button("OK", role: .cancel) { blockedDeleteKey = nil }
        } message: { key in
            Text(deleteBlockedMessage(key))
        }
    }

    private func deleteBlockedMessage(_ key: DeploySSHKey) -> String {
        let names = key.assignedServers(in: vpn.state.servers).map(\.displayName)
        let list = names.joined(separator: ", ")
        return "This key is assigned to \(list). Remove those servers from the library before deleting the key."
    }

    private func keyLabel(_ key: DeploySSHKey) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(key.displayName).font(.headline)
            Text(assignmentSummary(key))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func assignmentSummary(_ key: DeploySSHKey) -> String {
        let labels = key.assignments.map { $0.label(servers: vpn.state.servers) }
            .filter { !$0.isEmpty }
        switch labels.count {
        case 0: return "No assigned servers"
        case 1: return labels[0]
        default: return "\(labels.count) servers — \(labels[0])"
        }
    }
}

struct KeyGenerateView: View {
    @EnvironmentObject private var vpn: VPNController
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var privatePEM = ""
    @State private var publicLine = ""
    @State private var errorMessage: String?
    @State private var copied: String?

    private var hasKey: Bool { !privatePEM.isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Key name", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(hasKey)
                }

                if hasKey {
                    Section("Public key") {
                        Text(publicLine)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                        Button("Copy public key") { copy(publicLine, label: "Public key copied") }
                    }
                    Section("Private key") {
                        Text(privatePEM)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                        Button("Copy private key") { copy(privatePEM, label: "Private key copied") }
                    }
                    if let copied {
                        Section {
                            Text(copied).foregroundStyle(.secondary)
                        }
                    }
                } else {
                    Section {
                        Text("Creates an ED25519 key, saves it in the library, and shows both halves for copy.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Generate key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(hasKey ? "Done" : "Cancel") { dismiss() }
                }
                if !hasKey {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Create") { create() }
                    }
                }
            }
        }
    }

    private func create() {
        errorMessage = nil
        let comment = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = comment.isEmpty ? "hopper" : comment
        do {
            let generated = try SSHKeyGenerator.generateEd25519(comment: label)
            let key = DeploySSHKey(
                name: comment.isEmpty ? "hopper" : comment,
                privateKey: generated.privateKeyPEM
            )
            vpn.addDeployKey(key)
            privatePEM = generated.privateKeyPEM
            publicLine = generated.publicKeyLine
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func copy(_ text: String, label: String) {
        UIPasteboard.general.string = text
        copied = label
    }
}

struct KeyPasteView: View {
    @EnvironmentObject private var vpn: VPNController
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var pem = ""
    @State private var errorMessage: String?

    private var isValid: Bool {
        SSHKeyGenerator.isValidPrivateKey(pem)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Key name", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Private key") {
                    TextEditor(text: $pem)
                        .font(.system(.caption, design: .monospaced))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .frame(minHeight: 160)
                    if pem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text("Paste an OpenSSH ED25519 private key.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if isValid {
                        Text("Valid ED25519 private key.")
                            .font(.footnote)
                            .foregroundStyle(.green)
                    } else {
                        Text("Not a valid ED25519 private key.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Paste key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import") { importKey() }
                        .disabled(!isValid)
                }
            }
        }
    }

    private func importKey() {
        errorMessage = nil
        guard isValid else {
            errorMessage = "Not a valid ED25519 private key."
            return
        }
        let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
        vpn.importDeployKey(
            DeploySSHKey(
                name: label.isEmpty ? "Imported key" : label,
                privateKey: pem.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        )
        dismiss()
    }
}
