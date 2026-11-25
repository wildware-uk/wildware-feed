import ComposeApp
import StoreKit

/// Tells the Compose app which App Store storefront this iPhone buys from, at launch and whenever
/// it changes. App Store guidelines 3.1.1 and 3.1.3 allow a link to buying a subscription outside
/// the app only on the United States storefront, so the app offers the Team plan only there
/// (`AppStoreFront`, in Kotlin). Until this has run, and anywhere else, it offers nothing.
enum StorefrontWatch {
    static func run() async {
        AppStoreFront.shared.update(countryCode: await Storefront.current?.countryCode)
        for await front in Storefront.updates {
            AppStoreFront.shared.update(countryCode: front.countryCode)
        }
    }
}
