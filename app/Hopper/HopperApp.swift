import SwiftUI

@main
struct HopperApp: App {
    @StateObject private var vpn = VPNController()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(vpn)
                .onOpenURL { url in
                    vpn.handleIncomingHopperConfURL(url)
                }
                .sheet(item: $vpn.pendingLanInvite) { invite in
                    HopperLanSendView(invite: invite)
                        .environmentObject(vpn)
                }
        }
    }
}
