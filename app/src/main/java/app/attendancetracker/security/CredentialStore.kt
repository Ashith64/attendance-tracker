package app.attendancetracker.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "encrypted_credentials",
)

/**
 * Student portal login inputs. Never logged or persisted in plaintext.
 */
@Serializable
data class StudentCredentials(
    val usn: String,
    val dobDay: String,
    val dobMonth: String,
    val dobYear: String,
    val relation: Int,
    val digits: String,
) {
    override fun toString(): String = "StudentCredentials(<REDACTED>)"

    companion object {
        const val RELATION_FATHER = 1
        const val RELATION_MOTHER = 2
        const val RELATION_GUARDIAN = 5
    }
}

sealed class CredentialReadResult {
    data class Success(val credentials: StudentCredentials) : CredentialReadResult()
    data object NotFound : CredentialReadResult()
    data object AuthRequired : CredentialReadResult()
    data object KeyInvalidated : CredentialReadResult()
    data class Error(val reason: String) : CredentialReadResult()
}

interface CredentialStore {
    suspend fun hasCredentials(): Boolean
    suspend fun save(credentials: StudentCredentials): Boolean
    suspend fun read(): CredentialReadResult
    suspend fun clear()
}

/**
 * Encrypts [StudentCredentials] with an Android Keystore AES-256-GCM key and stores
 * only the ciphertext + IV in DataStore.
 *
 * Key properties:
 * - User authentication required (valid for 300s / 5 minutes after BiometricPrompt or device credential)
 * - Invalidated when biometric enrollment changes
 */
class AndroidCredentialStore(
    private val context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialStore {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DEFAULT_KEY_ALIAS = "app.attendancetracker.credentials.aes_gcm"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val AUTH_VALIDITY_SECONDS = 300
        private val KEY_CREDS_BLOB = stringPreferencesKey("creds_blob")
    }

    override suspend fun hasCredentials(): Boolean {
        val prefs = context.credentialDataStore.data.first()
        return !prefs[KEY_CREDS_BLOB].isNullOrBlank()
    }

    override suspend fun save(credentials: StudentCredentials): Boolean {
        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val plaintext = json.encodeToString(StudentCredentials.serializer(), credentials)
                .toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintext)
            plaintext.fill(0)

            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)

            context.credentialDataStore.edit { prefs ->
                prefs[KEY_CREDS_BLOB] = encoded
            }
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKeyOnly()
            false
        } catch (_: UserNotAuthenticatedException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun read(): CredentialReadResult {
        val prefs = context.credentialDataStore.data.first()
        val encoded = prefs[KEY_CREDS_BLOB]
        if (encoded.isNullOrBlank()) {
            return CredentialReadResult.NotFound
        }

        return try {
            val secretKey = getExistingKey() ?: return CredentialReadResult.KeyInvalidated
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH_BYTES) {
                return CredentialReadResult.Error("Corrupted credential payload")
            }
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            val decodedJson = plaintext.toString(Charsets.UTF_8)
            plaintext.fill(0)

            val credentials = json.decodeFromString(StudentCredentials.serializer(), decodedJson)
            CredentialReadResult.Success(credentials)
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear()
            CredentialReadResult.KeyInvalidated
        } catch (_: UserNotAuthenticatedException) {
            CredentialReadResult.AuthRequired
        } catch (e: Exception) {
            CredentialReadResult.Error(e.javaClass.simpleName)
        }
    }

    override suspend fun clear() {
        context.credentialDataStore.edit { prefs ->
            prefs.remove(KEY_CREDS_BLOB)
        }
        deleteKeyOnly()
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun deleteKeyOnly() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }
}
