import SwiftUI

struct ServerDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var vpn: VPNController
    let serverID: UUID

    @State private var name: String = ""
    @State private var exportMode: HopperExportMode?
    @State private var showDeleteConfirm = false

    private var server: HopNodeProfile? {
        vpn.state.server(id: serverID)
    }

    var body: some View {
        Group {
            if let server {
                Form {
                    Section("Name") {
                        TextField("Server name", text: $name)
                            .onChange(of: name) { _, newValue in
                                vpn.renameServer(id: serverID, name: newValue)
                            }
                    }

                    Section("Connection") {
                        LabeledContent("Host", value: server.trimmedHost)
                        LabeledContent("Port", value: String(server.port))
                        LabeledContent("User", value: server.trimmedUser)
                        if !server.installDir.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            LabeledContent("Install path", value: server.installDir)
                        }
                    }

                    Section("Share") {
                        HopperExportButtonsRow { exportMode = $0 }
                        Button("Delete", role: .destructive) { showDeleteConfirm = true }
                    }
                }
            } else {
                ContentUnavailableView("Server not found", systemImage: "server.rack")
            }
        }
        .navigationTitle(server?.displayName ?? "Server")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            name = server?.name ?? ""
        }
        .sheet(item: $exportMode) { mode in
            if let server {
                ServerExportView(server: server, mode: mode)
            }
        }
        .alert("Delete server?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                vpn.deleteServer(id: serverID)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let server {
                Text("Remove \(server.displayName) from the library. Chains that use this server will drop it.")
            }
        }
    }
}
