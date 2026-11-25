package dev.wildware.agents.app.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Apple's in-app purchase of the Team plan, on iPhone and iPad (StoreKit 2, in Swift:
 * `AppStoreBridge`). Null on every other platform, where "Upgrade" opens the billing site instead.
 *
 * A purchase comes back as a [SignedTransaction] on [signed]; the session sends it to the team's
 * dashboard, which has the billing service check it with Apple, then calls [finish]. Renewals and
 * restored purchases come the same way.
 */
interface AppStorePurchases {
    /** The Team products as this storefront prices them, once loaded; empty until then or when there are none. */
    val products: StateFlow<List<StoreProduct>>

    /** What the purchase under way is doing. */
    val purchase: StateFlow<PurchaseState>

    /** Purchases, renewals and restored purchases, signed by Apple, for the session to send on. */
    val signed: Flow<SignedTransaction>

    /** Loads (again) the products for [productIds]. */
    fun load(productIds: List<String>)

    /** Buys [productId] for the team whose App Store token is [accountToken]. */
    fun buy(productId: String, accountToken: String)

    /** Asks the App Store for this Apple ID's purchases again ("Restore purchases"). */
    fun restore()

    /** Opens Apple's own sheet to change or cancel the subscription. */
    fun manage()

    /** Tells the App Store [transactionId] is dealt with, once the dashboard has answered for it. */
    fun finish(transactionId: String)

    /** Back to [PurchaseState.Idle], once what happened has been shown. */
    fun clearPurchase()
}

/** One product as StoreKit has it here: [price] is a plain decimal ("34.99") in [currencyCode]. */
data class StoreProduct(val productId: String, val displayName: String, val displayPrice: String, val price: String, val currencyCode: String)

/** [jws] is the transaction StoreKit signed; [accountToken] the token it was bought with, if any. */
data class SignedTransaction(val id: String, val jws: String, val accountToken: String?)

sealed interface PurchaseState {
    data object Idle : PurchaseState
    data object Buying : PurchaseState
    /** Waiting on someone else, such as Ask to Buy. */
    data object Pending : PurchaseState
    data object Cancelled : PurchaseState
    data object Restoring : PurchaseState
    /** Bought and signed by Apple; the dashboard is being told. */
    data object Bought : PurchaseState
    data class Failed(val message: String) : PurchaseState
}
