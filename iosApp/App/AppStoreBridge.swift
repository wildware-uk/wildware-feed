import ComposeApp
import StoreKit
import UIKit

/// Buying the Team plan with StoreKit 2, for the Compose app (`IosAppStore`, in Kotlin).
///
/// It loads the two Team products, buys with the team's `appAccountToken` (from the dashboard's
/// plan), and hands Kotlin the signed form of every verified transaction: a purchase, a renewal, a
/// refund, or one restored. Kotlin sends it to the team's dashboard, which has the billing service
/// check it with Apple, and then asks for it to be finished. An unverified transaction is never sent.
///
/// Whether the app may also point to the website (the US storefront only) is `StorefrontWatch`'s.
final class AppStoreBridge: NSObject, AppStoreDriver {
    static let shared = AppStoreBridge()

    private let lock = NSLock()
    private var products: [String: Product] = [:]
    /// Transactions handed to Kotlin and not finished yet, by id.
    private var waiting: [UInt64: Transaction] = [:]

    /// Registers with Kotlin, then hands over what StoreKit has waiting and whatever comes later:
    /// renewals, refunds, and purchases made on another device or approved later (Ask to Buy).
    func run() async {
        IosAppStore.shared.register(driver: self)
        for await result in Transaction.unfinished { hand(result) }
        for await result in Transaction.updates { hand(result) }
    }

    private func hand(_ result: VerificationResult<Transaction>) {
        guard case .verified(let transaction) = result, transaction.productType == .autoRenewable else { return }
        lock.withLock { waiting[transaction.id] = transaction }
        IosAppStore.shared.transactionSigned(
            transactionId: String(transaction.id),
            jws: result.jwsRepresentation,
            accountToken: transaction.appAccountToken?.uuidString.lowercased()
        )
    }

    // MARK: AppStoreDriver, called from Kotlin

    func loadProducts(productIds: [String]) {
        Task {
            do {
                let found = try await Product.products(for: productIds)
                lock.withLock { for product in found { products[product.id] = product } }
                IosAppStore.shared.productsLoaded(products: found.map { product in
                    StoreProduct(
                        productId: product.id,
                        displayName: product.displayName,
                        displayPrice: product.displayPrice,
                        price: "\(product.price)",
                        currencyCode: product.priceFormatStyle.currencyCode
                    )
                })
            } catch {
                IosAppStore.shared.productsLoaded(products: [])
            }
        }
    }

    func buy(productId: String, accountToken: String) {
        Task {
            guard let token = UUID(uuidString: accountToken), let product = await product(productId) else {
                IosAppStore.shared.purchaseEnded(outcome: "failed", message: "That plan isn't for sale right now. Try again later.")
                return
            }
            do {
                let result: Product.PurchaseResult
                if let scene = await activeScene() {
                    result = try await product.purchase(confirmIn: scene, options: [.appAccountToken(token)])
                } else {
                    result = try await product.purchase(options: [.appAccountToken(token)])
                }
                switch result {
                case .success(let verification):
                    if case .verified = verification {
                        hand(verification)
                        IosAppStore.shared.purchaseEnded(outcome: "bought", message: nil)
                    } else {
                        IosAppStore.shared.purchaseEnded(outcome: "failed", message: "Apple couldn't confirm the purchase. Nothing was changed.")
                    }
                case .pending:
                    IosAppStore.shared.purchaseEnded(outcome: "pending", message: nil)
                case .userCancelled:
                    IosAppStore.shared.purchaseEnded(outcome: "cancelled", message: nil)
                @unknown default:
                    IosAppStore.shared.purchaseEnded(outcome: "idle", message: nil)
                }
            } catch {
                IosAppStore.shared.purchaseEnded(outcome: "failed", message: error.localizedDescription)
            }
        }
    }

    func restore() {
        Task {
            do {
                try await AppStore.sync()
            } catch {
                IosAppStore.shared.purchaseEnded(outcome: "failed", message: "Couldn't reach the App Store. Check you're signed in and try again.")
                return
            }
            var found = false
            for await result in Transaction.currentEntitlements {
                if case .verified(let transaction) = result, transaction.productType == .autoRenewable {
                    found = true
                    hand(result)
                }
            }
            IosAppStore.shared.purchaseEnded(
                outcome: found ? "idle" : "failed",
                message: found ? nil : "This Apple ID has no Team subscription to restore."
            )
        }
    }

    func manage() {
        Task { @MainActor in
            guard let scene = activeSceneNow() else { return }
            try? await AppStore.showManageSubscriptions(in: scene)
        }
    }

    func finish(transactionId: String) {
        guard let id = UInt64(transactionId) else { return }
        let transaction = lock.withLock { waiting.removeValue(forKey: id) }
        guard let transaction else { return }
        Task { await transaction.finish() }
    }

    // MARK: helpers

    private func product(_ id: String) async -> Product? {
        if let known = lock.withLock({ products[id] }) { return known }
        return try? await Product.products(for: [id]).first
    }

    private func activeScene() async -> UIWindowScene? {
        await MainActor.run { activeSceneNow() }
    }

    @MainActor
    private func activeSceneNow() -> UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }
}
