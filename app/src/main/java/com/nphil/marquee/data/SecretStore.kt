package com.nphil.marquee.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Plex sign-in: the account token and the server list, whose entries carry their own tokens. Each
 * value is sealed with AES-GCM under a key that never leaves the Android Keystore, in preferences that
 * are kept out of backups (res/xml/backup_rules.xml), so a copied data directory carries no usable token.
 */
class SecretStore(context: Context) {
	private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

	fun read(name: String): String? {
		val sealed = prefs.getString(name, null) ?: return null
		return try {
			val bytes = Base64.decode(sealed, Base64.NO_WRAP)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
			cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
		} catch (_: GeneralSecurityException) {
			// The key is gone (the Keystore was reset): what it sealed can never be read again.
			null
		} catch (_: IllegalArgumentException) {
			null
		}
	}

	fun write(name: String, value: String?) {
		if (value == null) {
			prefs.edit { remove(name) }
			return
		}
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key())
		val sealed = cipher.iv + cipher.doFinal(value.encodeToByteArray())
		prefs.edit { putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
	}

	fun clear() {
		prefs.edit { clear() }
	}

	private fun key(): SecretKey {
		val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
		return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
			init(
				KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setKeySize(256)
					.build()
			)
			generateKey()
		}
	}

	private companion object {
		const val KEYSTORE = "AndroidKeyStore"
		const val ALIAS = "marquee.secrets"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val TAG_BITS = 128
		const val IV_BYTES = 12
	}
}
