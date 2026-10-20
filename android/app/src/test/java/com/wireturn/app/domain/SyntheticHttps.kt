package com.wireturn.app.domain

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Scoped localhost TLS trust for tests; the system trust store and hostname verifier stay intact. */
internal object SyntheticHttps {
    val context: SSLContext by lazy {
        val password = "fixture-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        requireNotNull(javaClass.getResourceAsStream("/group-probe-fixture.p12")) {
            "Run tools/generate_test_tls.py before the JVM suite"
        }.use { store.load(it, password) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
    }
}
