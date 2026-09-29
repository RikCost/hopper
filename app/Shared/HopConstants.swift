import Foundation

enum HopConstants {
    static let appDisplayName = "ɹǝddoH"

    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.1.0"
    }

    static let appGroupID = "group.com.aengix.hopper"
    static let mainBundleID = "com.aengix.hopper"
    static let tunnelBundleID = "com.aengix.hopper.tunnel"

    static let profileStoreFileName = "hopper-profiles.json"
    static let tunnelLastErrorFileName = "last-tunnel-error.txt"
    static let deviceIDKey = "hopper-device-id"

    static let tunnelIPv4Mask = "255.255.255.0"
    static let tunnelRemoteAddress = "10.64.0.1"

    static let tunnelMTU = 1280

    static let defaultSSHPort = 22
    static let defaultInstallDir = "~/hopper"

    static let serverInstallRepo = "ZonD80/hopper"
    static let serverInstallRef = "main"
    static var serverInstallURL: String {
        "https://raw.githubusercontent.com/\(serverInstallRepo)/\(serverInstallRef)/server/install.sh"
    }

    /// First overlay address octet for hop index 0 (entry).
    static let overlayNodeOctet = 10
}
