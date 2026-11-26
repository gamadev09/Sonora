/*
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 */
package com.music.bitchord.playback.airplay.airplay2

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AirPlay2Crypto {

    companion object {
        fun generateCurve25519KeyPair(): AsymmetricCipherKeyPair {
            val generator = X25519KeyPairGenerator()
            generator.init(X25519KeyGenerationParameters(SecureRandom()))
            return generator.generateKeyPair()
        }

        fun curve25519Agree(privateKey: X25519PrivateKeyParameters, publicKey: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(privateKey)
            val shared = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), shared, 0)
            return shared
        }

        fun hkdfSha512(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val generator = HKDFBytesGenerator(SHA512Digest())
            generator.init(HKDFParameters(ikm, salt, info))
            val out = ByteArray(length)
            generator.generateBytes(out, 0, length)
            return out
        }

        fun chacha20Poly1305Encrypt(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray
        ): Pair<ByteArray, ByteArray> {
            val sealed = chacha20Poly1305Seal(key, nonce, plaintext, 0, plaintext.size, aad)
            val tagStart = sealed.size - 16
            return Pair(sealed.copyOfRange(0, tagStart), sealed.copyOfRange(tagStart, sealed.size))
        }

        fun chacha20Poly1305Seal(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            offset: Int,
            length: Int,
            aad: ByteArray
        ): ByteArray {
            val cipher = ChaCha20Poly1305()
            cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(cipher.getOutputSize(length))
            var n = cipher.processBytes(plaintext, offset, length, out, 0)
            n += cipher.doFinal(out, n)
            return if (n == out.size) out else out.copyOf(n)
        }

        fun chacha20Poly1305Decrypt(
            key: ByteArray,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
            tag: ByteArray
        ): ByteArray {
            val sealed = ciphertext + tag
            return chacha20Poly1305Open(key, nonce, sealed, 0, sealed.size, aad)
        }

        fun chacha20Poly1305Open(
            key: ByteArray,
            nonce: ByteArray,
            sealed: ByteArray,
            offset: Int,
            length: Int,
            aad: ByteArray
        ): ByteArray {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(cipher.getOutputSize(length))
            var n = cipher.processBytes(sealed, offset, length, out, 0)
            n += cipher.doFinal(out, n)
            return if (n == out.size) out else out.copyOf(n)
        }

        fun aes128CtrEncrypt(key: ByteArray, counter: ByteArray, data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val spec = SecretKeySpec(key, "AES")
            val iv = IvParameterSpec(counter)
            cipher.init(Cipher.ENCRYPT_MODE, spec, iv)
            return cipher.doFinal(data)
        }

        fun aes128CtrDecrypt(key: ByteArray, counter: ByteArray, data: ByteArray): ByteArray =
            aes128CtrEncrypt(key, counter, data)

        fun ed25519Sign(keyPair: AsymmetricCipherKeyPair, message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, keyPair.private as Ed25519PrivateKeyParameters)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            return signer.verifySignature(signature)
        }

        fun generateEd25519KeyPair(): AsymmetricCipherKeyPair {
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            return generator.generateKeyPair()
        }

        fun getEd25519PrivateKey(pair: AsymmetricCipherKeyPair): ByteArray =
            (pair.private as Ed25519PrivateKeyParameters).encoded

        fun ed25519KeyPairFromSeed(seed: ByteArray): AsymmetricCipherKeyPair {
            val priv = Ed25519PrivateKeyParameters(seed, 0)
            return AsymmetricCipherKeyPair(priv.generatePublicKey(), priv)
        }

        fun getEd25519PublicKey(pair: AsymmetricCipherKeyPair): ByteArray =
            (pair.public as Ed25519PublicKeyParameters).encoded

        fun getPrivateKeyBytes(keyPair: AsymmetricCipherKeyPair): ByteArray =
            (keyPair.private as X25519PrivateKeyParameters).encoded

        fun getPublicKeyBytes(keyPair: AsymmetricCipherKeyPair): ByteArray =
            (keyPair.public as X25519PublicKeyParameters).encoded

        fun buildPairCipherKeys(sharedSecret: ByteArray, channel: Int = 0): PairKeysResult {
            val salt = "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8)
            val infoEncrypt = "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8)
            val infoDecrypt = "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8)
            val encKey = hkdfSha512(salt, sharedSecret, infoEncrypt, 32)
            val decKey = hkdfSha512(salt, sharedSecret, infoDecrypt, 32)
            return if (channel == 0) {
                PairKeysResult(encKey, decKey, 0L, 0L)
            } else {
                val eventEncInfo = "Events-Write-Encryption-Key".toByteArray(Charsets.UTF_8)
                val eventDecInfo = "Events-Read-Encryption-Key".toByteArray(Charsets.UTF_8)
                val eventEncKey = hkdfSha512(salt, sharedSecret, eventEncInfo, 32)
                val eventDecKey = hkdfSha512(salt, sharedSecret, eventDecInfo, 32)
                PairKeysResult(eventEncKey, eventDecKey, 0L, 0L)
            }
        }
    }

    data class PairKeysResult(
        val encryptionKey: ByteArray,
        val decryptionKey: ByteArray,
        var encryptionCounter: Long,
        var decryptionCounter: Long
    )
}
