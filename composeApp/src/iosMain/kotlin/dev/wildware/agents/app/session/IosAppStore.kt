package dev.wildware.agents.app.session

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * What Swift does with StoreKit 2 for [IosAppStore] (`AppStoreBridge.swift`). Every call returns at
 * once; Swift answers through [IosAppStore]'s functions.
 */
interface AppStoreDriver {
    fun loadProducts(productIds: List<String>)
    fun buy(productId: String, accountToken: String)
    fun restore()
    fun manage()
    fun finish(transactionId: String)
}

/**
 * The iPhone's App Store in-app purchase for the Compose app ([AppStorePurchases]). Swift registers
 * its [AppStoreDriver] at launch and reports back here. Until it has, nothing can be bought and the
 * paywall waits for prices.
 */
object IosAppStore : AppStorePurchases {
    @kotlin.concurrent.Volatile
    private var driver: AppStoreDriver? = null

    private val mutableProducts = MutableStateFlow<List<StoreProduct>>(emptyList())
    private val mutablePurchase = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    private val transactions = Channel<SignedTransaction>(Channel.UNLIMITED)

    override val products: StateFlow<List<StoreProduct>> = mutableProducts.asStateFlow()
    override val purchase: StateFlow<PurchaseState> = mutablePurchase.asStateFlow()
    override val signed: Flow<SignedTransaction> = transactions.receiveAsFlow()

    // ---- from Swift

    fun register(driver: AppStoreDriver) {
        this.driver = driver
    }

    /** The products StoreKit found, each as id, name, display price, decimal price, currency code. */
    fun productsLoaded(products: List<StoreProduct>) {
        mutableProducts.value = products
    }

    /** A transaction StoreKit verified: a purchase, a renewal, or one restored. [accountToken] may be null. */
    fun transactionSigned(transactionId: String, jws: String, accountToken: String?) {
        transactions.trySend(SignedTransaction(transactionId, jws, accountToken))
    }

    /** How the purchase went: "bought", "pending", "cancelled", "idle", or "failed" with [message]. */
    fun purchaseEnded(outcome: String, message: String?) {
        mutablePurchase.value = when (outcome) {
            "bought" -> PurchaseState.Bought
            "pending" -> PurchaseState.Pending
            "cancelled" -> PurchaseState.Cancelled
            "failed" -> PurchaseState.Failed(message ?: "The App Store couldn't finish the purchase. Try again.")
            else -> PurchaseState.Idle
        }
    }

    // ---- from the app

    override fun load(productIds: List<String>) {
        driver?.loadProducts(productIds)
    }

    override fun buy(productId: String, accountToken: String) {
        val d = driver ?: return run { mutablePurchase.value = PurchaseState.Failed("The App Store isn't ready yet. Try again in a moment.") }
        mutablePurchase.value = PurchaseState.Buying
        d.buy(productId, accountToken)
    }

    override fun restore() {
        val d = driver ?: return
        mutablePurchase.value = PurchaseState.Restoring
        d.restore()
    }

    override fun manage() {
        driver?.manage()
    }

    override fun finish(transactionId: String) {
        driver?.finish(transactionId)
    }

    override fun clearPurchase() {
        mutablePurchase.value = PurchaseState.Idle
    }
}
