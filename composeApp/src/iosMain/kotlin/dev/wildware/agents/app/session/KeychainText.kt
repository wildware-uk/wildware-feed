package dev.wildware.agents.app.session

import dev.wildware.agents.TextStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * One text in the iPhone's Keychain: the teams this phone is signed in to, keys included. It sits
 * beside the open team's own key (Swift's `CredentialStore`), in the same shared Keychain group and
 * readable after the first unlock as that one is, so the app's other parts could read it too.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainText(
    private val service: String = "dev.wildware.agents.teams",
    private val account: String = "teams",
) : TextStore {
    override fun read(): String? = memScoped {
        val query = query()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun write(text: String) {
        val match = query()
        SecItemDelete(match)
        CFRelease(match)
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val add = query()
        val value: CFTypeRef? = CFBridgingRetain(data)
        CFDictionaryAddValue(add, kSecValueData, value)
        CFBridgingRelease(value)
        // Notification actions and widgets run while the phone is locked, after its first unlock.
        CFDictionaryAddValue(add, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        SecItemAdd(add, null)
        CFRelease(add)
    }

    /** The item's class, service, account and group, in a dictionary the caller releases. */
    private fun query(): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        put(query, kSecAttrService, service)
        put(query, kSecAttrAccount, account)
        sharedGroup?.let { put(query, kSecAttrAccessGroup, it) }
        return query
    }

    private fun put(query: CFMutableDictionaryRef?, key: CFTypeRef?, text: String) {
        val value: CFTypeRef? = CFBridgingRetain(NSString.create(string = text))
        CFDictionaryAddValue(query, key, value)
        CFBridgingRelease(value)
    }

    /** Swift's shared group: the team prefix is only known at run time, from the app's own Info.plist. */
    private val sharedGroup: String? =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("AppIdentifierPrefix") as? String)?.let { "${it}dev.wildware.agents.shared" }
}
