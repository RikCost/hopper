import SwiftUI

struct ChainDetailView: View {
    @EnvironmentObject private var vpn: VPNController
    let chainID: UUID

    @State private var name: String = ""
    @State private var statusLoading = false
    @State private var exportMode: HopperExportMode?

    private var chain: HopChain? {
        vpn.state.chains.first { $0.id == chainID }
    }

    private var hops: [HopNodeProfile] {
        guard let chain else { return [] }
        return vpn.state.resolveHops(chain)
    }

    private var statusReports: [ChainStatusReport] {
        vpn.chainStatusReports[chainID] ?? []
    }

    var body: some View {
        Group {
            if chain != nil {
                Form {
                    Section("Name") {
                        TextField("Chain name", text: $name)
                            .onChange(of: name) { _, newValue in
                                vpn.renameChain(id: chainID, name: newValue)
                            }
                    }

                    Section("Status") {
                        Button(statusLoading ? "Loading…" : "Refresh status") {
                            Task {
                                statusLoading = true
                                await vpn.fetchChainStatus(chainID: chainID)
                                statusLoading = false
                            }
                        }
                        .disabled(hops.isEmpty || statusLoading)

                        if statusReports.isEmpty && !statusLoading {
                            Text("Tap Refresh to query each hop.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        ForEach(Array(statusReports.enumerated()), id: \.offset) { index, report in
                            let hop = index < hops.count ? hops[index] : nil
                            VStack(alignment: .leading, spacing: 4) {
                                Text(hop?.displayName ?? report.host ?? "Hop")
                                    .font(.headline)
                                if let version = report.serverVersion {
                                    Text("Server v\(version)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                ForEach(report.chains) { entry in
                                    Text("\(entry.role ?? "?") · \(entry.running == true ? "running" : "stopped") · \(entry.sessions.count) session(s)")
                                        .font(.caption2)
                                    if let last = entry.lastActivity {
                                        Text("Last activity: \(last)")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }

                    Section("Route (entry → exit)") {
                        if hops.isEmpty {
                            Text("Add servers from your library.")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(Array(hops.enumerated()), id: \.element.id) { index, hop in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(roleLabel(index: index, total: hops.count, hop: hop))
                                        .font(.headline)
                                    Text("\(hop.trimmedUser)@\(hop.trimmedHost):\(hop.port)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .onDelete { offsets in
                                vpn.removeHopFromChain(chainID: chainID, at: offsets)
                            }
                            .onMove { source, destination in
                                vpn.moveHopInChain(chainID: chainID, from: source, to: destination)
                            }
                        }

                        NavigationLink {
                            ServerLibraryView(chainID: chainID)
                        } label: {
                            Text("Add server…")
                        }
                    }

                    if !hops.isEmpty {
                        Section("Share") {
                            HopperExportButtonsRow { exportMode = $0 }
                        }
                    }
                }
            } else {
                ContentUnavailableView("Chain not found", systemImage: "link.badge.plus")
            }
        }
        .navigationTitle(chain?.displayName ?? "Chain")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if !hops.isEmpty {
                    EditButton()
                }
            }
        }
        .sheet(item: $exportMode) { mode in
            ChainExportView(chainName: chain?.name ?? "", hops: hops, mode: mode)
        }
        .onAppear {
            name = chain?.name ?? ""
        }
    }

    private func roleLabel(index: Int, total: Int, hop: HopNodeProfile) -> String {
        let role: String
        if total == 1 {
            role = "Exit"
        } else if index == 0 {
            role = "Entry"
        } else if index == total - 1 {
            role = "Exit"
        } else {
            role = "Relay"
        }
        return "\(index + 1). \(role) — \(hop.displayName)"
    }
}
