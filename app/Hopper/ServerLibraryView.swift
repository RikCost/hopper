import SwiftUI

struct ServerLibraryView: View {
    @EnvironmentObject private var vpn: VPNController
    @Environment(\.dismiss) private var dismiss
    var chainID: UUID? = nil

    @State private var showScanner = false
    @State private var importMode: HopperImportMode?
    @State private var showDeploy = false

    private var isPickMode: Bool { chainID != nil }

    private var displayedServers: [HopNodeProfile] {
        guard let chainID else { return vpn.state.servers }
        guard let chain = vpn.state.chains.first(where: { $0.id == chainID }) else { return [] }
        let used = Set(chain.hopIDs)
        return vpn.state.servers.filter { !used.contains($0.id) }
    }

    var body: some View {
        List {
            Section {
                HopperImportButtons { importMode = $0 }
            }
            if displayedServers.isEmpty {
                ContentUnavailableView(
                    "No servers",
                    systemImage: "server.rack",
                    description: Text(emptyDescription)
                )
            } else {
                ForEach(displayedServers) { server in
                    if isPickMode, let chainID {
                        Button {
                            vpn.addServerToChain(chainID: chainID, serverID: server.id)
                            dismiss()
                        } label: {
                            serverLabel(server)
                        }
                        .foregroundStyle(.primary)
                    } else {
                        NavigationLink {
                            ServerDetailView(serverID: server.id)
                        } label: {
                            serverLabel(server)
                        }
                    }
                }
                .onDelete { offsets in
                    guard !isPickMode else { return }
                    let idsToDelete = Set(offsets.map { displayedServers[$0].id })
                    var indices = IndexSet()
                    for (index, server) in vpn.state.servers.enumerated() where idsToDelete.contains(server.id) {
                        indices.insert(index)
                    }
                    if !indices.isEmpty {
                        vpn.deleteServers(at: indices)
                    }
                }
            }
        }
        .navigationTitle(isPickMode ? "Add server" : "Servers")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button("Deploy") { showDeploy = true }
                Button("Scan QR") { showScanner = true }
            }
        }
        .sheet(isPresented: $showScanner) {
            QRCodeScannerView { payload in
                if vpn.handleScannedQR(payload) {
                    showScanner = false
                }
            }
        }
        .sheet(isPresented: $showDeploy) {
            DeployServerView()
        }
        .sheet(item: $importMode) { mode in
            HopperImportSheet(mode: mode) { importMode = nil }
        }
    }

    private var emptyDescription: String {
        if isPickMode {
            return "Deploy a server, scan a QR code, or import a .hopperconf file, then tap to add to this chain."
        }
        return "Deploy a server, scan a QR code, or import a .hopperconf file."
    }

    @ViewBuilder
    private func serverLabel(_ server: HopNodeProfile) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(server.displayName).font(.headline)
            Text("\(server.trimmedUser)@\(server.trimmedHost):\(server.port)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
