import SwiftUI
import UIKit

struct KeyDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var vpn: VPNController
    let keyID: UUID

    @State private var name: String = ""
    @State private var showPrivate = false
    @State private var exportMode: HopperExportMode?
    @State private var showDeleteConfirm = false
    @State private var showDeleteBlocked = false
    @State private var copied: String?

    private var canDelete: Bool {
        key?.canDelete(servers: vpn.state.servers) ?? false
    }

    private var key: DeploySSHKey? {
        vpn.state.deployKey(id: keyID)
    }

    var body: some View {
        Group {
            if let key {
                Form {
                    Section("Name") {
                        TextField("Key name", text: $name)
                            .onChange(of: name) { _, newValue in
                                vpn.renameDeployKey(id: keyID, name: newValue)
                            }
                    }

                    Section("Public key") {
                        Text(key.publicKeyLine ?? "Could not derive public key.")
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                        if let publicLine = key.publicKeyLine {
                            Button("Copy public key") { copy(publicLine, label: "Public key copied") }
                        }
                    }

                    Section("Private key") {
                        if showPrivate {
                            Text(key.privateKey)
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                        } else {
                            Text("Hidden")
                                .foregroundStyle(.secondary)
                        }
                        Button(showPrivate ? "Hide private key" : "Show private key") {
                            showPrivate.toggle()
                        }
                        Button("Copy private key") { copy(key.privateKey, label: "Private key copied") }
                    }

                    Section("Assigned servers") {
                        let labels = key.assignments.map { $0.label(servers: vpn.state.servers) }
                            .filter { !$0.isEmpty }
                        if labels.isEmpty {
                            Text("No assigned servers yet. Deploy with this key to record user@host.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(labels, id: \.self) { label in
                                Text(label)
                            }
                        }
                    }

                    if let copied {
                        Section {
                            Text(copied).foregroundStyle(.secondary)
                        }
                    }

                    Section("Share") {
                        HopperExportButtonsRow { exportMode = $0 }
                        Button("Delete", role: .destructive) {
                            if canDelete {
                                showDeleteConfirm = true
                            } else {
                                showDeleteBlocked = true
                            }
                        }
                        if !canDelete {
                            Text("Remove assigned servers from the library before deleting this key.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } else {
                ContentUnavailableView("Key not found", systemImage: "key")
            }
        }
        .navigationTitle(key?.displayName ?? "Key")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            name = key?.name ?? ""
        }
        .sheet(item: $exportMode) { mode in
            if let key {
                KeyExportView(key: key, mode: mode)
            }
        }
        .alert("Delete key?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                vpn.deleteDeployKey(id: keyID)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let key {
                Text("Remove \(key.displayName) from the library. Servers keep their own hop keys.")
            }
        }
        .alert("Can't delete key", isPresented: $showDeleteBlocked) {
            Button("OK", role: .cancel) {}
        } message: {
            if let key {
                let names = key.assignedServers(in: vpn.state.servers).map(\.displayName).joined(separator: ", ")
                Text("This key is assigned to \(names). Remove those servers from the library before deleting the key.")
            }
        }
    }

    private func copy(_ text: String, label: String) {
        UIPasteboard.general.string = text
        copied = label
    }
}
