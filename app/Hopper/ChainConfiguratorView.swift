import SwiftUI

struct ChainConfiguratorView: View {
    @EnvironmentObject private var vpn: VPNController
    @State private var chainToDelete: HopChain?
    @State private var showScanner = false
    @State private var importMode: HopperImportMode?

    var body: some View {
        List {
            Section {
                NavigationLink {
                    ServerLibraryView()
                } label: {
                    Label("Server library", systemImage: "server.rack")
                }
                NavigationLink {
                    KeyLibraryView()
                } label: {
                    Label("Keys library", systemImage: "key")
                }
            } footer: {
                Text("Manage servers and deploy keys, or scan / import a shared chain, server, or key below.")
            }

            Section("Chains") {
                if vpn.state.chains.isEmpty {
                    Text("Create a chain and add servers in entry → exit order.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(vpn.state.chains) { chain in
                        NavigationLink {
                            ChainDetailView(chainID: chain.id)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(chain.displayName).font(.headline)
                                    Text(chainSummary(chain))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if vpn.state.selectedChainID == chain.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.green)
                                }
                            }
                        }
                        .swipeActions(edge: .leading) {
                            Button("Use") {
                                vpn.selectChain(chain.id)
                            }
                            .tint(.blue)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                chainToDelete = chain
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                    .onDelete { offsets in vpn.deleteChains(at: offsets) }
                }

                Button {
                    let id = vpn.addChain()
                    vpn.selectChain(id)
                } label: {
                    Label("New chain", systemImage: "plus")
                }

                Button {
                    showScanner = true
                } label: {
                    Label("Scan QR", systemImage: "qrcode.viewfinder")
                }

                HopperImportButtons { importMode = $0 }
            }
        }
        .navigationTitle("Chains")
        .toolbar {
            if !vpn.state.chains.isEmpty {
                EditButton()
            }
        }
        .sheet(isPresented: $showScanner) {
            QRCodeScannerView { payload in
                if vpn.handleScannedQR(payload) {
                    showScanner = false
                }
            }
        }
        .sheet(item: $importMode) { mode in
            HopperImportSheet(mode: mode) { importMode = nil }
        }
        .alert("Delete chain?", isPresented: Binding(
            get: { chainToDelete != nil },
            set: { if !$0 { chainToDelete = nil } }
        ), presenting: chainToDelete) { chain in
            Button("Delete", role: .destructive) {
                deleteChain(chain)
            }
            Button("Cancel", role: .cancel) {
                chainToDelete = nil
            }
        } message: { chain in
            Text("Remove \(chain.displayName) from the library. Servers in your library are kept.")
        }
    }

    private func deleteChain(_ chain: HopChain) {
        guard let index = vpn.state.chains.firstIndex(where: { $0.id == chain.id }) else {
            chainToDelete = nil
            return
        }
        vpn.deleteChains(at: IndexSet(integer: index))
        chainToDelete = nil
    }

    private func chainSummary(_ chain: HopChain) -> String {
        let hops = vpn.state.resolveHops(chain)
        if hops.isEmpty { return "No servers" }
        if hops.count == 1 { return "1 hop — \(hops[0].displayName)" }
        return "\(hops.count) hops — \(hops.first!.displayName) → \(hops.last!.displayName)"
    }
}
