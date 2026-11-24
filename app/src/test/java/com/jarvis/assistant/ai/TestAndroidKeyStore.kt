package com.jarvis.assistant.ai

import org.junit.rules.ExternalResource
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no AndroidKeyStore; exercise the real AES-GCM path with a test-only JCA key. */
internal class TestAndroidKeyStore : ExternalResource() {
    @Suppress("DEPRECATION")
    private val provider = object : Provider("AshTestCredentialStore", 1.0, "Test-only AndroidKeyStore stand-in") {
        init { put("KeyStore.AndroidKeyStore", TestKeyStoreSpi::class.java.name) }
    }
    private var installed = false

    override fun before() { installed = Security.insertProviderAt(provider, 1) > 0 }

    override fun after() {
        if (installed) Security.removeProvider(provider.name)
    }
}

class TestKeyStoreSpi : KeyStoreSpi() {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    override fun engineGetKey(alias: String?, password: CharArray?): Key = key
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = error("read-only")
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = error("read-only")
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = error("read-only")
    override fun engineDeleteEntry(alias: String?) = error("read-only")
    override fun engineAliases() = Collections.emptyEnumeration<String>()
    override fun engineContainsAlias(alias: String?) = true
    override fun engineSize() = 1
    override fun engineIsKeyEntry(alias: String?) = true
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
