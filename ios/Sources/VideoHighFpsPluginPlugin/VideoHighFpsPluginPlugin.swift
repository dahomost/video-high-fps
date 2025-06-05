import Foundation
import Capacitor

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(VideoHighFpsPlugin)
public class VideoHighFpsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "VideoHighFpsPlugin"
    public let jsName = "VideoHighFpsPlugin"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "echo", returnType: CAPPluginReturnPromise)
    ]
    private let implementation = VideoHighFpsPlugin()

    @objc func echo(_ call: CAPPluginCall) {
        let value = call.getString("value") ?? ""
        call.resolve([
            "value": implementation.echo(value)
        ])
    }
}
