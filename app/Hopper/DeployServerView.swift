import SwiftUI

private enum DeployAuthMode: String, CaseIterable, Identifiable {
    case password
    case savedKey

    var id: String { rawValue }

    var label: String {
        switch self {
        case .password: return "Password"
        case .savedKey: return "Saved key"
        }
    }
}

struct DeployServerView: View {
    @EnvironmentObject private var vpn: VPNController
    @Environment(\.dismiss) private var dismiss

    @State private var host = ""
    @State private var user = "root"
    @State private var portText = "22"
    @State private var password = ""
    @State private var authMode: DeployAuthMode = .password
    @State private var selectedKeyID: UUID?
    @State private var isDeploying = false
    @State private var errorMessage: String?
    @State private var deployLog = ""

    var body: some View {
        NavigationStack {
            Form {
                if !isDeploying {
                    Section("Server") {
                        TextField("Host or IP", text: $host)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        TextField("User", text: $user)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("SSH port", text: $portText)
                            .keyboardType(.numberPad)
                    }

                    Section("Authentication") {
                        Picker("Method", selection: $authMode) {
                            ForEach(DeployAuthMode.allCases) { mode in
                                Text(mode.label).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        switch authMode {
                        case .password:
                            SecureField("Password", text: $password)
                            Text("A new deploy key is generated, saved in the Keys library, and installed on the server.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        case .savedKey:
                            if vpn.state.deployKeys.isEmpty {
                                Text("No keys in the library yet. Generate or paste one in Keys library, or use password once.")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            } else {
                                Picker("Deploy key", selection: Binding(
                                    get: { selectedKeyID ?? vpn.state.deployKeys.first?.id },
                                    set: { selectedKeyID = $0 }
                                )) {
                                    ForEach(vpn.state.deployKeys) { key in
                                        Text(key.name).tag(Optional(key.id))
                                    }
                                }
                            }
                        }
                    }
                }

                if isDeploying || !deployLog.isEmpty {
                    Section("Deploy log") {
                        ScrollViewReader { proxy in
                            ScrollView {
                                Text(deployLog.isEmpty ? "Starting…" : deployLog)
                                    .font(.system(.caption, design: .monospaced))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .textSelection(.enabled)
                                    .id("deployLogEnd")
                            }
                            .frame(minHeight: 180, maxHeight: 320)
                            .onChange(of: deployLog) { _, _ in
                                withAnimation(.easeOut(duration: 0.15)) {
                                    proxy.scrollTo("deployLogEnd", anchor: .bottom)
                                }
                            }
                        }
                        if isDeploying {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("Deploying…")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .font(.footnote)
                    }
                }
            }
            .navigationTitle("Deploy Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isDeploying)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Deploy") { deploy() }
                        .disabled(!canDeploy || isDeploying)
                }
            }
            .interactiveDismissDisabled(isDeploying)
        }
        .onAppear {
            if selectedKeyID == nil {
                selectedKeyID = vpn.state.deployKeys.first?.id
            }
        }
    }

    private var canDeploy: Bool {
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch authMode {
        case .password:
            return !password.isEmpty
        case .savedKey:
            return selectedKeyID != nil || !vpn.state.deployKeys.isEmpty
        }
    }

    private func appendLog(_ line: String) {
        if deployLog.isEmpty {
            deployLog = line
        } else {
            deployLog += "\n" + line
        }
    }

    private func deploy() {
        errorMessage = nil
        deployLog = ""
        isDeploying = true
        let port = Int(portText.trimmingCharacters(in: .whitespacesAndNewlines)) ?? HopConstants.defaultSSHPort
        let auth: ServerDeployAuth
        switch authMode {
        case .password:
            auth = .password(password)
        case .savedKey:
            guard let keyID = selectedKeyID ?? vpn.state.deployKeys.first?.id,
                  let key = vpn.state.deployKey(id: keyID) else {
                errorMessage = ServerDeployerError.missingDeployKey.localizedDescription
                isDeploying = false
                return
            }
            auth = .deployKey(key)
        }

        Task {
            do {
                try await vpn.deployServer(
                    host: host,
                    port: port,
                    user: user,
                    auth: auth,
                    onLog: { line in
                        Task { @MainActor in
                            appendLog(line)
                        }
                    }
                )
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
            isDeploying = false
        }
    }
}
