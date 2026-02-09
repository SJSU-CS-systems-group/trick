import Foundation
#if canImport(WiFiAware)
import WiFiAware

@available(iOS 26, *)
extension WAPublishableService {
    static var trickService: WAPublishableService? {
        return allServices["_trick-msg._tcp"]
    }
}

@available(iOS 26, *)
extension WASubscribableService {
    static var trickService: WASubscribableService? {
        return allServices["_trick-msg._tcp"]
    }
}
#endif
