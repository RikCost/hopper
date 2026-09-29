import SwiftUI

private enum AddServerAuthMode: String, CaseIterable, Identifiable {
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

struct AddServerView: View {
    @EnvironmentObject private var vpn: VPNController
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var host = ""
    @State private var user = "root"
    @State private var portText = "22"
    @State private var password = ""
    @State private var authMode: AddServerAuthMode = .password
    @State private var selectedKeyID: UUID?
    @State private var isAdding = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Name (optional)", text: $name)
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
                        ForEach(AddServerAuthMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(isAdding)

                    switch authMode {
                    case .password:
                        SecureField("Password", text: $password)
                            .disabled(isAdding)
                        Text("A new key is generated, saved in the Keys library, and authorized on the server. Hopper is not installed.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    case .savedKey:
                        if vpn.state.deployKeys.isEmpty {
                            Text("No keys in the library yet. Generate or paste one in Keys library, or use password once.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else {
                            Picker("SSH key", selection: Binding(
                                get: { selectedKeyID ?? vpn.state.deployKeys.first?.id },
                                set: { selectedKeyID = $0 }
                            )) {
                                ForEach(vpn.state.deployKeys) { key in
                                    Text(key.name).tag(Optional(key.id))
                                }
                            }
                            .disabled(isAdding)
                            Text("Adds this server to the library using the selected key. No remote install is run.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if isAdding {
                    Section {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("Adding…")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
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
            .navigationTitle("Add Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isAdding)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }
                        .disabled(!canAdd || isAdding)
                }
            }
            .interactiveDismissDisabled(isAdding)
        }
        .onAppear {
            if selectedKeyID == nil {
                selectedKeyID = vpn.state.deployKeys.first?.id
            }
            if vpn.state.deployKeys.isEmpty {
                authMode = .password
            }
        }
    }

    private var canAdd: Bool {
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch authMode {
        case .password:
            return !password.isEmpty
        case .savedKey:
            return selectedKeyID != nil || !vpn.state.deployKeys.isEmpty
        }
    }

    private func add() {
        errorMessage = nil
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty else {
            errorMessage = "Host is required."
            return
        }
        let port = Int(portText.trimmingCharacters(in: .whitespacesAndNewlines)) ?? HopConstants.defaultSSHPort
        let effectivePort = port > 0 ? port : HopConstants.defaultSSHPort
        let trimmedUser = user.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveUser = trimmedUser.isEmpty ? "root" : trimmedUser
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        switch authMode {
        case .savedKey:
            guard let keyID = selectedKeyID ?? vpn.state.deployKeys.first?.id,
                  let key = vpn.state.deployKey(id: keyID) else {
                errorMessage = "Select an SSH key from the library."
                return
            }
            let trimmedKey = key.privateKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedKey.isEmpty else {
                errorMessage = "Selected key has no private key material."
                return
            }
            let profile = HopNodeProfile(
                name: trimmedName,
                host: trimmedHost,
                port: effectivePort,
                user: effectiveUser,
                privateKey: key.privateKey,
                installDir: HopConstants.defaultInstallDir
            )
            vpn.addServer(profile)
            vpn.recordDeployKeyUse(id: key.id, server: profile)
            dismiss()

        case .password:
            guard !password.isEmpty else {
                errorMessage = "Password is required."
                return
            }
            isAdding = true
            Task {
                do {
                    let generated = try SSHKeyGenerator.generateEd25519(comment: "hopper-deploy@\(trimmedHost)")
                    var key = DeploySSHKey(
                        name: "Deploy \(effectiveUser)@\(trimmedHost)",
                        privateKey: generated.privateKeyPEM
                    )
                    let publicLine = try SSHKeyGenerator.publicKeyLine(
                        privateKeyPEM: generated.privateKeyPEM,
                        comment: "deploy-\(trimmedHost)"
                    )
                    try await DeploySSH.authorizeKey(
                        host: trimmedHost,
                        port: effectivePort,
                        user: effectiveUser,
                        password: password,
                        privateKeyPEM: generated.privateKeyPEM,
                        publicKeyLine: publicLine
                    )
                    let profile = HopNodeProfile(
                        name: trimmedName,
                        host: trimmedHost,
                        port: effectivePort,
                        user: effectiveUser,
                        privateKey: generated.privateKeyPEM,
                        installDir: HopConstants.defaultInstallDir
                    )
                    key.recordAssignment(from: profile)
                    vpn.addDeployKey(key)
                    vpn.addServer(profile)
                    dismiss()
                } catch {
                    errorMessage = error.localizedDescription
                    isAdding = false
                }
            }
        }
    }
}
