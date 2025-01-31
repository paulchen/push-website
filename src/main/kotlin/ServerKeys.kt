package at.rueckgr

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.*


class ServerKeys {
    companion object {
        private var instance: ServerKeys? = null

        fun getInstance(): ServerKeys {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = ServerKeys()
                    }
                }
            }
            return instance!!
        }
    }

    val publicKey: ECPublicKey
    val privateKey: ECPrivateKey
    val publicKeyBase64: String
    val publicKeyUncompressed: ByteArray

    init {
        val appServerPublicKeyFile: Path = Paths.get("key.public")
        val appServerPrivateKeyFile: Path = Paths.get("key.private")

        // TODO persistence
        val cryptoService = CryptoService.getInstance()
        if (Files.exists(appServerPublicKeyFile) && Files.exists(appServerPrivateKeyFile)) {
            val appServerPublicKey = Files.readAllBytes(appServerPublicKeyFile)
            val appServerPrivateKey = Files.readAllBytes(appServerPrivateKeyFile)

            publicKey = cryptoService.convertX509ToECPublicKey(appServerPublicKey) as ECPublicKey
            privateKey = cryptoService.convertPKCS8ToECPrivateKey(appServerPrivateKey) as ECPrivateKey

        } else {
            val pair: KeyPair = cryptoService.keyPairGenerator.generateKeyPair()

            publicKey = pair.public as ECPublicKey
            privateKey = pair.private as ECPrivateKey

            Files.write(appServerPublicKeyFile, publicKey.encoded)
            Files.write(appServerPrivateKeyFile, privateKey.encoded)
        }

        publicKeyUncompressed = cryptoService.toUncompressedECPublicKey(publicKey)
        publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(this.publicKeyUncompressed)
    }
}
