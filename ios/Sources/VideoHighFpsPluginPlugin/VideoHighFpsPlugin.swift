import Foundation

@objc public class VideoHighFpsPlugin: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
