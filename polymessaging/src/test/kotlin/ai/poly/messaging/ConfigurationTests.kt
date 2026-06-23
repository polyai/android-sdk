// Copyright PolyAI Limited

package ai.poly.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests of the [PolyMessaging.configure] entry point. The signature takes a [Context];
 * Robolectric supplies a real application context so `PolyMessagingClient.create` can read
 * `applicationContext` / `packageName`.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigurationTests {

    companion object {
        init {
            // TEST WIRING (not an SDK change): configure() eagerly builds SessionStore, whose
            // EncryptedSharedPreferences master key lives in the "AndroidKeyStore" JCA provider.
            // That provider exists on devices but not on the Robolectric JVM, so
            // MasterKeys.getOrCreate threw KeyStoreException("AndroidKeyStore not
            // found"). Install an in-memory stand-in provider so the production code path runs
            // unmodified. androidx MasterKeys needs KeyStore.{load,containsAlias} +
            // KeyGenerator("AES"); Tink additionally fetches the key via KeyStore.getKey.
            FakeAndroidKeyStore.install()
        }
    }

    @Test
    fun configure_validApiKey_returnsClientWithConfig() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = PolyMessaging.configure(context, Configuration(apiKey = "test_api_key", environment = Environment.US))
        assertEquals("test_api_key", client.config.apiKey)
    }

    @Test
    fun configure_emptyApiKey_throwsInvalidConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The empty-key check throws before the context is ever touched.
        assertFailsWith<PolyError.InvalidConfiguration> {
            PolyMessaging.configure(context, Configuration(apiKey = "", environment = Environment.US))
        }
    }
}

/**
 * A minimal in-memory "AndroidKeyStore" JCA provider for JVM unit tests. Keys live in a
 * plain map; security is irrelevant — the point is that androidx MasterKeys / Tink's
 * AndroidKeystoreKmsClient find a provider with this name and a working AES KeyGenerator,
 * so EncryptedSharedPreferences can be constructed under Robolectric.
 *
 * Registered Spi instances are created via overridden [Provider.Service.newInstance]
 * (not reflective class-name lookup), so the Spi classes can stay file-private.
 */
// Note: the (String, double, String) constructor is the only one in android.jar (the
// JDK-9 String-version overload is absent), hence the deprecated-on-JVM double form.
@Suppress("DEPRECATION")
private object FakeAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "Fake AndroidKeyStore for JVM unit tests") {

    // Named storedKeys (not `keys`) to avoid hiding java.util.Hashtable.keys inherited via Provider.
    val storedKeys = ConcurrentHashMap<String, Key>()

    init {
        putService(object : Service(this, "KeyStore", "AndroidKeyStore", FakeKeyStoreSpi::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?) = FakeKeyStoreSpi()
        })
        putService(object : Service(this, "KeyGenerator", "AES", FakeAesKeyGeneratorSpi::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?) = FakeAesKeyGeneratorSpi()
        })
    }

    fun install() {
        // Security is JVM-global (shared across Robolectric sandboxes); only register once.
        if (Security.getProvider(name) == null) {
            Security.addProvider(this)
        }
    }
}

private class FakeKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = FakeAndroidKeyStore.storedKeys[alias]
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date = Date()
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
        FakeAndroidKeyStore.storedKeys[alias] = key
    }

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?): Unit =
        throw UnsupportedOperationException("opaque key bytes are not supported by the fake AndroidKeyStore")

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?): Unit =
        throw UnsupportedOperationException("certificates are not supported by the fake AndroidKeyStore")

    override fun engineDeleteEntry(alias: String) {
        FakeAndroidKeyStore.storedKeys.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(FakeAndroidKeyStore.storedKeys.keys)
    override fun engineContainsAlias(alias: String): Boolean = FakeAndroidKeyStore.storedKeys.containsKey(alias)
    override fun engineSize(): Int = FakeAndroidKeyStore.storedKeys.size
    override fun engineIsKeyEntry(alias: String): Boolean = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String?): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) {} // in-memory only
    override fun engineLoad(stream: InputStream?, password: CharArray?) {} // MasterKeys calls load(null)
}

private class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
    private var keystoreAlias: String? = null

    override fun engineInit(random: SecureRandom?) {}
    override fun engineInit(keysize: Int, random: SecureRandom?) {}

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        // android.security.keystore.KeyGenParameterSpec is loaded by the Robolectric sandbox
        // classloader; reach the alias reflectively so this Spi works from any sandbox.
        keystoreAlias = params?.let { it.javaClass.getMethod("getKeystoreAlias").invoke(it) as? String }
    }

    override fun engineGenerateKey(): SecretKey {
        val material = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(material, "AES")
        // AndroidKeyStore semantics: the generated key is persisted under its spec alias.
        keystoreAlias?.let { FakeAndroidKeyStore.storedKeys[it] = key }
        return key
    }
}
