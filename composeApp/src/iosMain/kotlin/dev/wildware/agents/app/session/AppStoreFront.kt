package dev.wildware.agents.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which App Store storefront this iPhone buys from, as Swift reads it from StoreKit
 * (`Storefront.current`, and again on `Storefront.updates`). App Store guidelines 3.1.1 and 3.1.3
 * allow a button or link to buying a digital subscription outside the app only on the United
 * States storefront, so the app offers the Team plan, and links to the website, only there
 * ([AppPlatform.buyingLinks]). Until Swift has said, and anywhere else, it offers nothing.
 */
object AppStoreFront {
    private val links = MutableStateFlow(false)

    internal val buyingLinks: StateFlow<Boolean> = links.asStateFlow()

    /** The storefront's ISO 3166-1 alpha-3 code, such as "USA", or null when StoreKit has none. */
    fun update(countryCode: String?) {
        links.value = dev.wildware.agents.app.feed.Upsell.storefrontAllowsLinks(countryCode)
    }
}
