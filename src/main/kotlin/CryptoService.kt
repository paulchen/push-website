package at.rueckgr

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max


class CryptoService private constructor() {
    companion object {
        private var instance: CryptoService? = null

        fun getInstance(): CryptoService {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = CryptoService()
                    }
                }
            }
            return instance!!
        }
    }

    private val P256_HEAD = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgA")

    val keyPairGenerator: KeyPairGenerator = KeyPairGenerator.getInstance("EC")

    private val keyFactory: KeyFactory
    private val secureRandom: SecureRandom

    init {
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        keyFactory = KeyFactory.getInstance("EC")
        secureRandom = SecureRandom()
    }

    fun toUncompressedECPublicKey(publicKey: ECPublicKey): ByteArray {
        val result = ByteArray(65)
        val encoded: ByteArray = publicKey.encoded
        System.arraycopy(publicKey.encoded, P256_HEAD.size, result, 0,encoded.size - P256_HEAD.size)
        return result
    }

    private fun fromUncompressedECPublicKey(encodedPublicKey: String): ECPublicKey {
        val w = Base64.getUrlDecoder().decode(encodedPublicKey)
        val encodedKey = ByteArray(P256_HEAD.size + w.size)
        System.arraycopy(P256_HEAD, 0, encodedKey, 0, P256_HEAD.size)
        System.arraycopy(w, 0, encodedKey, P256_HEAD.size, w.size)

        return keyFactory.generatePublic(X509EncodedKeySpec(encodedKey)) as ECPublicKey
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val result = ByteArray(arrays.sumOf { it.size })

        // copy the source arrays into the result array
        var currentIndex = 0
        arrays.forEach {
            System.arraycopy(it, 0, result, currentIndex, it.size)
            currentIndex += it.size
        }

        return result
    }

    fun encrypt(plainTextString: String, uaPublicKeyString: String, authSecret: String, paddingSize: Int): ByteArray {

        val asKeyPair = keyPairGenerator.genKeyPair()
        val asPublicKey = asKeyPair.public as ECPublicKey
        val uncompressedASPublicKey: ByteArray = toUncompressedECPublicKey(asPublicKey)

        val uaPublicKey: ECPublicKey = fromUncompressedECPublicKey(uaPublicKeyString)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(asKeyPair.private)
        keyAgreement.doPhase(uaPublicKey, true)

        val ecdhSecret = keyAgreement.generateSecret()

        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)

        // ## Use HKDF to combine the ECDH and authentication secrets
        // # HKDF-Extract(salt=auth_secret, IKM=ecdh_secret)
        // PRK_key = HMAC-SHA-256(auth_secret, ecdh_secret)
        val hmacSHA256 = Mac.getInstance("HmacSHA256")
        hmacSHA256
            .init(SecretKeySpec(Base64.getUrlDecoder().decode(authSecret), "HmacSHA256"))
        val prkKey = hmacSHA256.doFinal(ecdhSecret)

        // # HKDF-Expand(PRK_key, key_info, L_key=32)
        // key_info = "WebPush: info" || 0x00 || ua_public || as_public
        val keyInfo: ByteArray = concat(
            "WebPush: info\u0000".toByteArray(Charsets.UTF_8),
            toUncompressedECPublicKey(uaPublicKey), uncompressedASPublicKey
        )
        // IKM = HMAC-SHA-256(PRK_key, key_info || 0x01)
        hmacSHA256.init(SecretKeySpec(prkKey, "HmacSHA256"))
        hmacSHA256.update(keyInfo)
        hmacSHA256.update(1.toByte())
        val ikm = hmacSHA256.doFinal()

        // ## HKDF calculations from RFC 8188
        // # HKDF-Extract(salt, IKM)
        // PRK = HMAC-SHA-256(salt, IKM)
        hmacSHA256.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = hmacSHA256.doFinal(ikm)

        // # HKDF-Expand(PRK, cek_info, L_cek=16)
        // cek_info = "Content-Encoding: aes128gcm" || 0x00
        val cekInfo: ByteArray = "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.UTF_8)
        // CEK = HMAC-SHA-256(PRK, cek_info || 0x01)[0..15]
        hmacSHA256.init(SecretKeySpec(prk, "HmacSHA256"))
        hmacSHA256.update(cekInfo)
        hmacSHA256.update(1.toByte())
        var cek = hmacSHA256.doFinal()
        cek = Arrays.copyOfRange(cek, 0, 16)

        // # HKDF-Expand(PRK, nonce_info, L_nonce=12)
        // nonce_info = "Content-Encoding: nonce" || 0x00
        val nonceInfo: ByteArray = "Content-Encoding: nonce\u0000".toByteArray(Charsets.UTF_8)
        // NONCE = HMAC-SHA-256(PRK, nonce_info || 0x01)[0..11]
        hmacSHA256.init(SecretKeySpec(prk, "HmacSHA256"))
        hmacSHA256.update(nonceInfo)
        hmacSHA256.update(1.toByte())
        var nonce = hmacSHA256.doFinal()
        nonce = Arrays.copyOfRange(nonce, 0, 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"),
            GCMParameterSpec(128, nonce)
        )

        val inputs: MutableList<ByteArray> = ArrayList()
        val plainTextBytes: ByteArray = plainTextString.toByteArray(Charsets.UTF_8)
        inputs.add(plainTextBytes)
        inputs.add(byteArrayOf(2)) // padding delimiter

        val padSize = max(0.0, (paddingSize - plainTextBytes.size).toDouble()).toInt()
        if (padSize > 0) {
            inputs.add(ByteArray(padSize))
        }

        val encrypted = cipher.doFinal(concat(*inputs.toTypedArray()))

        val encryptedArrayLength: ByteBuffer = ByteBuffer.allocate(4)
        encryptedArrayLength.putInt(encrypted.size)

        val header: ByteArray = concat(
            salt, encryptedArrayLength.array(),
            byteArrayOf(uncompressedASPublicKey.size.toByte()), uncompressedASPublicKey
        )

        return concat(header, encrypted)
    }

    fun convertX509ToECPublicKey(encodedPublicKey: ByteArray): PublicKey =
        keyFactory.generatePublic(X509EncodedKeySpec(encodedPublicKey))

    fun convertPKCS8ToECPrivateKey(encodedPrivateKey: ByteArray): PrivateKey =
        keyFactory.generatePrivate(PKCS8EncodedKeySpec(encodedPrivateKey))
}
