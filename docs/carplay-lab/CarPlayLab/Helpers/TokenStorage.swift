/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
Maintains the Apple Music user token in a device-limited keychain item.
*/

import Foundation

// Struct `TokenStorage` wraps the SecItem… functions necessary for storing a user token
// in the keychain.

// Store an Apple Music user token on-device, but ensure it isn’t transferrable to other devices, even devices with the same logged-in iCloud account.
struct TokenStorage {
    
    /// Key for the `UserDefaults` value that represents the UUID of the service name that this app uses.
    private static let serviceNameKey = "serviceNameKey"
    
    /// The stored user token.
    private(set) var tokenText = ""
    
    /// A unique service name for a keychain item.
    private let serviceName: String
    
    /// An arbitrary account name for a keychain password item.
    private let accountName = "Apple Music user token"
    
    /// 'true' if a stored user token is available.
    var isStored: Bool {
        return tokenText != ""
    }
    
    /// Initialize a new TokenStorage object.
    init() {
        // Retrieve the service name from user defaults, if it exists.
        if let serviceName = UserDefaults.standard.string(forKey: TokenStorage.serviceNameKey) {
            self.serviceName = serviceName
        }
        
        // Otherwise, create and store a new service name.
        else {
            let serviceName = UUID().uuidString
            UserDefaults.standard.set(serviceName, forKey: TokenStorage.serviceNameKey)
            self.serviceName = serviceName
        }
        
        // If you’ve already stored the user token, retrieve it.
        if let tokenText = existingTokenText {
            self.tokenText = tokenText
        }
    }
    
    /// Find an existing user token in the keychain.
    private var existingTokenText: String? {
        
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: serviceName,
                                    kSecMatchLimit as String: kSecMatchLimitOne,
                                    kSecReturnAttributes as String: true,
                                    kSecReturnData as String: true]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        
        if status == errSecSuccess,
            let existingItem = item as? [String: Any],
            let passwordData = existingItem[kSecValueData as String] as? Data {
            
            return String(data: passwordData, encoding: String.Encoding.utf8)
        }
        
        return nil
    }
    
    /// Store a new user token in the keychain.
    mutating func store(tokenText newTokenText: String) throws {
        
        // Replace the current user token.
        tokenText = newTokenText
        
        let passwordData = tokenText.data(using: String.Encoding.utf8)!
        let status: OSStatus
        
        // If you didn’t previously store the user token, create a new item.
        // Otherwise, update the existing item.
        if existingTokenText == nil {
            let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                        kSecAttrAccount as String: accountName,
                                        kSecAttrService as String: serviceName,
                                        kSecValueData as String: passwordData]
            status = SecItemAdd(query as CFDictionary, nil)
        } else {
            let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                        kSecAttrService as String: serviceName]
            let update: [String: Any] = [kSecValueData as String: passwordData,
                                         kSecAttrAccount as String: accountName]
            status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        }
        
        // Convert a failure status into an error.
        guard status == errSecSuccess else {
            tokenText = ""
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
    
    /// Invalidate the user token.
    mutating func remove() throws {
        try store(tokenText: "")
    }
    
}
