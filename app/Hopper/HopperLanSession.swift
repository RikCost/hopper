import Foundation
import Network
import Security

/// Resumes a checked continuation at most once from `@Sendable` Network callbacks.
private final class ResumeOnce<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?

    init(_ continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    func succeed(_ value: T) {
        take()?.resume(returning: value)
    }

    func fail(_ error: Error) {
        take()?.resume(throwing: error)
    }

    @discardableResult
    func take() -> CheckedContinuation<T, Error>? {
        lock.lock()
        defer { lock.unlock() }
        let value = continuation
        continuation = nil
        return value
    }
}

private extension ResumeOnce where T == Void {
    func succeed() { succeed(()) }
}

final class HopperLanConnection {
    private let connection: NWConnection
    private let queue: DispatchQueue

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let once = ResumeOnce(cont)
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    once.succeed()
                case .failed(let error):
                    once.fail(error)
                case .cancelled:
                    once.fail(HopperLanError.disconnected)
                default:
                    break
                }
            }
            connection.start(queue: queue)
        }
    }

    func sendJSON(_ object: [String: Any]) async throws {
        let body = try HopperLanWire.encode(object)
        guard body.count <= HopperLanInvite.maxFrameBytes else { throw HopperLanError.badMessage }
        let frame = HopperLanWire.frame(body)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let once = ResumeOnce(cont)
            connection.send(content: frame, completion: .contentProcessed { error in
                if let error {
                    once.fail(error)
                } else {
                    once.succeed()
                }
            })
        }
    }

    func receiveJSON() async throws -> [String: Any] {
        let header = try await receiveExact(4)
        guard header.count == 4 else { throw HopperLanError.badMessage }
        let b0 = UInt32(header[0])
        let b1 = UInt32(header[1])
        let b2 = UInt32(header[2])
        let b3 = UInt32(header[3])
        let count = Int((b0 << 24) | (b1 << 16) | (b2 << 8) | b3)
        guard count > 0, count <= HopperLanInvite.maxFrameBytes else { throw HopperLanError.badMessage }
        let body = try await receiveExact(count)
        return try HopperLanWire.decode(body)
    }

    func cancel() {
        connection.cancel()
    }

    private func receiveExact(_ count: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            receiveExact(count, collected: Data(), once: ResumeOnce(cont))
        }
    }

    private func receiveExact(_ remaining: Int, collected: Data, once: ResumeOnce<Data>) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, isComplete, error in
            if let error {
                once.fail(error)
                return
            }
            if isComplete && (data == nil || data?.isEmpty == true) {
                once.fail(HopperLanError.disconnected)
                return
            }
            var next = collected
            if let data { next.append(data) }
            let consumed = data?.count ?? 0
            let left = remaining - consumed
            if left == 0 {
                once.succeed(next)
            } else if left > 0 {
                self.receiveExact(left, collected: next, once: once)
            } else {
                once.fail(HopperLanError.badMessage)
            }
        }
    }
}

enum HopperLanTLS {
    static func serverParameters(identity: SecIdentity) throws -> NWParameters {
        let tls = NWProtocolTLS.Options()
        guard let secIdentity = sec_identity_create(identity) else {
            throw HopperLanError.identityFailed
        }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, secIdentity)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        let params = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        params.acceptLocalOnly = false
        params.allowLocalEndpointReuse = true
        params.includePeerToPeer = true
        return params
    }

    static func clientParameters(fingerprint: String) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                  let cert = chain.first else {
                complete(false)
                return
            }
            let der = SecCertificateCopyData(cert) as Data
            complete(HopperLanCertificate.fingerprint(der: der) == fingerprint)
        }, DispatchQueue.global(qos: .userInitiated))
        let params = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        params.includePeerToPeer = true
        return params
    }
}

@MainActor
final class HopperLanReceiver: ObservableObject {
    enum Phase: Equatable {
        case starting
        case listening
        case connected
        case failed(String)
    }

    @Published private(set) var phase: Phase = .starting
    @Published private(set) var invite: HopperLanInvite?
    @Published private(set) var received: [String] = []

    private var identity: HopperLanCertificate.Identity?
    private var listener: NWListener?
    private var session: HopperLanConnection?
    private let queue = DispatchQueue(label: "hopper.lan.recv")
    private var onImport: ((HopperConf.Payload) -> String)?
    private var acceptTask: Task<Void, Never>?

    func start(onImport: @escaping (HopperConf.Payload) -> String) {
        self.onImport = onImport
        acceptTask = Task { await listenThenServe() }
    }

    func stop() {
        acceptTask?.cancel()
        session?.cancel()
        listener?.cancel()
        identity?.dispose()
        identity = nil
        listener = nil
        session = nil
    }

    private func listenThenServe() async {
        do {
            guard let ip = HopperLanAddress.advertisedIPv4() else { throw HopperLanError.noLanAddress }
            let identity = try HopperLanCertificate.makeEphemeral()
            self.identity = identity
            let params = try HopperLanTLS.serverParameters(identity: identity.secIdentity)
            let listener = try NWListener(using: params, on: 0)
            self.listener = listener

            let inbound = try await acceptFirstConnection(
                listener,
                ip: ip,
                fingerprint: identity.fingerprint
            )

            let session = HopperLanConnection(connection: inbound, queue: queue)
            self.session = session
            try await session.start()
            self.phase = .connected
            try await serve(session)
        } catch is CancellationError {
            // dismissed
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    /// Starts the listener, shows the QR once the port is bound, then returns the first client.
    private func acceptFirstConnection(
        _ listener: NWListener,
        ip: String,
        fingerprint: String
    ) async throws -> NWConnection {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<NWConnection, Error>) in
            let once = ResumeOnce(cont)

            listener.newConnectionHandler = { conn in
                if let pending = once.take() {
                    listener.cancel()
                    pending.resume(returning: conn)
                } else {
                    conn.cancel()
                }
            }
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    Task { @MainActor in
                        do {
                            try self.publishInvite(listener: listener, ip: ip, fingerprint: fingerprint)
                        } catch {
                            once.fail(error)
                        }
                    }
                case .failed(let error):
                    once.fail(error)
                case .cancelled:
                    once.fail(CancellationError())
                default:
                    break
                }
            }
            listener.start(queue: self.queue)
        }
    }

    private func publishInvite(listener: NWListener, ip: String, fingerprint: String) throws {
        if invite != nil { return }
        guard !ip.isEmpty,
              let port = listener.port?.rawValue, port > 0,
              let invite = HopperLanInvite.make(ip: ip, port: port, fingerprint: fingerprint) else {
            throw HopperLanError.listenFailed
        }
        self.invite = invite
        self.phase = .listening
    }

    private func serve(_ session: HopperLanConnection) async throws {
        let hello = try await session.receiveJSON()
        guard hello[HopperLanWire.type] as? String == HopperLanWire.hello else {
            throw HopperLanError.badMessage
        }
        try await session.sendJSON([HopperLanWire.type: HopperLanWire.ready])
        while !Task.isCancelled {
            let message = try await session.receiveJSON()
            let type = message[HopperLanWire.type] as? String
            if type == HopperLanWire.bye { return }
            guard type == HopperLanWire.item,
                  let object = message[HopperLanWire.payload] as? [String: Any] else {
                try await session.sendJSON([
                    HopperLanWire.type: HopperLanWire.err,
                    HopperLanWire.error: HopperLanError.badMessage.errorDescription ?? "Invalid message.",
                ])
                continue
            }
            do {
                let payload = try HopperConf.parsePayloadObject(object)
                let summary = onImport?(payload) ?? "Imported."
                received.append(summary)
                try await session.sendJSON([
                    HopperLanWire.type: HopperLanWire.ok,
                    HopperLanWire.summary: summary,
                ])
            } catch {
                try await session.sendJSON([
                    HopperLanWire.type: HopperLanWire.err,
                    HopperLanWire.error: error.localizedDescription,
                ])
            }
        }
    }
}

@MainActor
final class HopperLanSender: ObservableObject {
    @Published private(set) var status: String = "Connecting…"
    @Published private(set) var ready = false
    @Published var log: [String] = []

    private var session: HopperLanConnection?
    private let queue = DispatchQueue(label: "hopper.lan.send")
    private let invite: HopperLanInvite

    init(invite: HopperLanInvite) {
        self.invite = invite
    }

    func start() {
        Task { await connect() }
    }

    func stop() {
        Task {
            try? await session?.sendJSON([HopperLanWire.type: HopperLanWire.bye])
            session?.cancel()
            session = nil
        }
    }

    func share(_ payload: HopperConf.Payload) async {
        guard let session, ready else { return }
        do {
            let object = try HopperConf.exportPayloadDictionary(payload)
            try await session.sendJSON([
                HopperLanWire.type: HopperLanWire.item,
                HopperLanWire.payload: object,
            ])
            let reply = try await session.receiveJSON()
            let type = reply[HopperLanWire.type] as? String
            if type == HopperLanWire.ok, let summary = reply[HopperLanWire.summary] as? String {
                log.append(summary)
                status = summary
            } else {
                let message = reply[HopperLanWire.error] as? String ?? "Share failed."
                log.append(message)
                status = message
            }
        } catch {
            status = error.localizedDescription
            log.append(error.localizedDescription)
        }
    }

    private func connect() async {
        do {
            guard let port = NWEndpoint.Port(rawValue: invite.port) else { throw HopperLanError.connectFailed }
            let params = HopperLanTLS.clientParameters(fingerprint: invite.fingerprint)
            let connection = NWConnection(host: NWEndpoint.Host(invite.ip), port: port, using: params)
            let session = HopperLanConnection(connection: connection, queue: queue)
            self.session = session
            try await session.start()
            try await session.sendJSON([
                HopperLanWire.type: HopperLanWire.hello,
                HopperLanWire.version: HopperLanInvite.currentVersion,
            ])
            let reply = try await session.receiveJSON()
            guard reply[HopperLanWire.type] as? String == HopperLanWire.ready else {
                throw HopperLanError.badMessage
            }
            ready = true
            status = "Connected. Choose a chain, server, or key to share."
        } catch {
            status = error.localizedDescription
            ready = false
        }
    }
}
