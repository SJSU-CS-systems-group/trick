import Foundation
import LibSignalClient

@_cdecl("testSwiftLibSignalAvailability")
func testSwiftLibSignalAvailability() -> Bool {
    do {
        _ = PrivateKey.generate()
        return true
    } catch { return false }
}

@_cdecl("getSwiftLibSignalVersion")
func getSwiftLibSignalVersion() -> UnsafePointer<CChar>? {
    let version = "LibSignalClient Swift"
    return unsafeBitCast(strdup(version), to: UnsafePointer<CChar>?.self)
}

@_cdecl("freeSwiftString")
func freeSwiftString(_ string: UnsafePointer<CChar>) { free(UnsafeMutablePointer(mutating: string)) }

@_cdecl("generatePrivateKeyData")
func generatePrivateKeyData(_ buffer: UnsafeMutablePointer<UInt8>, _ bufferSize: Int32) -> Int32 {
    do {
        let pk = PrivateKey.generate().serialize()
        guard pk.count <= Int(bufferSize) else { return -1 }
        pk.withUnsafeBytes { src in buffer.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: pk.count) }
        return Int32(pk.count)
    } catch { return -2 }
}

@_cdecl("getPublicKeyFromPrivate")
func getPublicKeyFromPrivate(_ privateKeyData: UnsafePointer<UInt8>, _ privateKeySize: Int32,
                             _ publicKeyBuffer: UnsafeMutablePointer<UInt8>, _ bufferSize: Int32) -> Int32 {
    do {
        let pkData = Data(bytes: privateKeyData, count: Int(privateKeySize))
        let pk = try PrivateKey(pkData)
        let pub = pk.publicKey.serialize()
        guard pub.count <= Int(bufferSize) else { return -1 }
        pub.withUnsafeBytes { src in publicKeyBuffer.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: pub.count) }
        return Int32(pub.count)
    } catch { return -2 }
}

@_cdecl("signData")
func signData(_ privateKeyData: UnsafePointer<UInt8>, _ privateKeySize: Int32,
              _ data: UnsafePointer<UInt8>, _ dataSize: Int32,
              _ signatureBuffer: UnsafeMutablePointer<UInt8>, _ bufferSize: Int32) -> Int32 {
    do {
        let pkData = Data(bytes: privateKeyData, count: Int(privateKeySize))
        let msg = Data(bytes: data, count: Int(dataSize))
        let pk = try PrivateKey(pkData)
        let sig = pk.generateSignature(message: msg)
        guard sig.count <= Int(bufferSize) else { return -1 }
        sig.withUnsafeBytes { src in signatureBuffer.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: sig.count) }
        return Int32(sig.count)
    } catch { return -2 }
}

@_cdecl("verifySignature")
func verifySignature(_ publicKeyData: UnsafePointer<UInt8>, _ publicKeySize: Int32,
                     _ data: UnsafePointer<UInt8>, _ dataSize: Int32,
                     _ signature: UnsafePointer<UInt8>, _ signatureSize: Int32) -> Int32 {
    do {
        let pubData = Data(bytes: publicKeyData, count: Int(publicKeySize))
        let msg = Data(bytes: data, count: Int(dataSize))
        let sig = Data(bytes: signature, count: Int(signatureSize))
        let pub = try PublicKey(pubData)
        return try pub.verifySignature(message: msg, signature: sig) ? 1 : 0
    } catch { return -1 }
}

@_cdecl("hpkeSeal")
func hpkeSeal(_ publicKeyData: UnsafePointer<UInt8>, _ publicKeySize: Int32,
              _ message: UnsafePointer<UInt8>, _ messageSize: Int32,
              _ outBuffer: UnsafeMutablePointer<UInt8>, _ outBufferSize: Int32) -> Int32 {
    do {
        let pubData = Data(bytes: publicKeyData, count: Int(publicKeySize))
        let msg = Data(bytes: message, count: Int(messageSize))
        let pub = try PublicKey(pubData)
        // Use a fixed info label for demo; can be parameterized later
        let ct = pub.seal(msg, info: "net.discdd.trick.libsignal.hpke")
        guard ct.count <= Int(outBufferSize) else { return -1 }
        ct.withUnsafeBytes { src in outBuffer.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: ct.count) }
        return Int32(ct.count)
    } catch { return -2 }
}

@_cdecl("hpkeOpen")
func hpkeOpen(_ privateKeyData: UnsafePointer<UInt8>, _ privateKeySize: Int32,
              _ ciphertext: UnsafePointer<UInt8>, _ ciphertextSize: Int32,
              _ outBuffer: UnsafeMutablePointer<UInt8>, _ outBufferSize: Int32) -> Int32 {
    do {
        let pkData = Data(bytes: privateKeyData, count: Int(privateKeySize))
        let ct = Data(bytes: ciphertext, count: Int(ciphertextSize))
        let pk = try PrivateKey(pkData)
        let pt = try pk.open(ct, info: "net.discdd.trick.libsignal.hpke")
        guard pt.count <= Int(outBufferSize) else { return -1 }
        pt.withUnsafeBytes { src in outBuffer.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: pt.count) }
        return Int32(pt.count)
    } catch { return -2 }
}


