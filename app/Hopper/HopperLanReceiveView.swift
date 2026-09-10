import SwiftUI

struct HopperLanReceiveView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var vpn: VPNController
    @StateObject private var receiver = HopperLanReceiver()
    @State private var toast: String?

    var body: some View {
        NavigationStack {
            Group {
                switch receiver.phase {
                case .starting:
                    Text("Starting a local receive session…")
                        .foregroundStyle(.secondary)
                        .padding()
                case .failed(let message):
                    Text(message)
                        .foregroundStyle(.red)
                        .padding()
                case .listening, .connected:
                    VStack(spacing: 12) {
                        if let invite = receiver.invite {
                            Text("Open Camera on the other Hopper device and scan this code. Then choose what to share.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                            if let qr = HopQRCodeImage.make(from: invite.url.absoluteString) {
                                HopFitQRCode(image: qr)
                            }
                            Text("\(invite.ip):\(invite.port)")
                                .font(.footnote.monospaced())
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .hopperToast($toast)
                    .safeAreaInset(edge: .bottom) {
                        if case .connected = receiver.phase {
                            Button("Disconnect", role: .destructive) {
                                receiver.stop()
                                dismiss()
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.large)
                            .frame(maxWidth: .infinity)
                            .padding(.horizontal)
                            .padding(.bottom, 8)
                        }
                    }
                }
            }
            .navigationTitle("Import remotely")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") {
                        receiver.stop()
                        dismiss()
                    }
                }
            }
            .onChange(of: receiver.phase) { _, phase in
                if case .connected = phase {
                    toast = "Somebody connected. You can keep receiving until you disconnect."
                }
            }
            .onChange(of: receiver.received.count) { _, _ in
                if let last = receiver.received.last {
                    toast = last
                }
            }
            .onAppear {
                receiver.start { payload in
                    vpn.importPayload(payload, announceChain: false)
                }
            }
            .onDisappear {
                receiver.stop()
            }
        }
    }
}

struct HopperLanSendView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var vpn: VPNController
    @StateObject private var sender: HopperLanSender
    @State private var toast: String?

    init(invite: HopperLanInvite) {
        _sender = StateObject(wrappedValue: HopperLanSender(invite: invite))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(sender.status)
                        .foregroundStyle(sender.ready ? .primary : .secondary)
                }

                if sender.ready {
                    if !vpn.state.chains.isEmpty {
                        Section("Chains") {
                            ForEach(vpn.state.chains) { chain in
                                let hops = vpn.state.resolveHops(chain)
                                Button {
                                    Task {
                                        await sender.share(.chain(name: chain.name, hops: hops))
                                    }
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(chain.displayName)
                                        Text("\(hops.count) server(s)")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .disabled(hops.isEmpty)
                            }
                        }
                    }

                    if !vpn.state.servers.isEmpty {
                        Section("Servers") {
                            ForEach(vpn.state.servers) { server in
                                Button(server.displayName) {
                                    Task { await sender.share(.server(server)) }
                                }
                            }
                        }
                    }

                    if !vpn.state.deployKeys.isEmpty {
                        Section("Keys") {
                            ForEach(vpn.state.deployKeys) { key in
                                Button(key.displayName) {
                                    Task { await sender.share(.key(key)) }
                                }
                            }
                        }
                    }

                    if vpn.state.chains.isEmpty && vpn.state.servers.isEmpty && vpn.state.deployKeys.isEmpty {
                        Section {
                            Text("Nothing to share yet. Add a server, chain, or key first.")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Share")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") {
                        sender.stop()
                        dismiss()
                    }
                }
            }
            .hopperToast($toast)
            .onChange(of: sender.log.count) { _, _ in
                if let last = sender.log.last {
                    toast = last
                }
            }
            .onAppear { sender.start() }
            .onDisappear { sender.stop() }
        }
    }
}

struct HopperImportSheet: View {
    let mode: HopperImportMode
    let onImported: () -> Void
    @EnvironmentObject private var vpn: VPNController

    var body: some View {
        switch mode {
        case .file:
            HopImportView(mode: .file) { payload in
                _ = vpn.importPayload(payload)
                onImported()
            }
        case .paste:
            HopImportView(mode: .paste) { payload in
                _ = vpn.importPayload(payload)
                onImported()
            }
        case .remote:
            HopperLanReceiveView()
        }
    }
}

private struct HopperToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message {
                    Text(message)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                        .padding(.horizontal, 20)
                        .padding(.bottom, 12)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.2), value: message)
            .onChange(of: message) { _, newValue in
                guard let newValue else { return }
                Task {
                    try? await Task.sleep(for: .seconds(2.4))
                    if message == newValue {
                        message = nil
                    }
                }
            }
    }
}

private extension View {
    func hopperToast(_ message: Binding<String?>) -> some View {
        modifier(HopperToastModifier(message: message))
    }
}
