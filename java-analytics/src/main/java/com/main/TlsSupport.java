package com.main;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * Serves the analytics API over TLS when a keystore is configured. Without this the API key
 * travels in plaintext on every request an agent makes.
 */
public final class TlsSupport {
    private TlsSupport() {
    }

    /**
     * Creates the listener the configuration asks for: HTTPS when TLS is enabled, plain HTTP
     * otherwise so a local checkout still runs with no certificate to manage.
     */
    public static com.sun.net.httpserver.HttpServer createServer(EngineConfiguration configuration)
            throws IOException {
        InetSocketAddress address = new InetSocketAddress(configuration.port());
        if (!configuration.tlsEnabled()) {
            return com.sun.net.httpserver.HttpServer.create(address, 0);
        }
        if (configuration.tlsKeystorePath() == null || configuration.tlsKeystorePath().isBlank()) {
            throw new IOException("TLS_ENABLED is set but TLS_KEYSTORE_PATH is empty");
        }

        HttpsServer server = HttpsServer.create(address, 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext(configuration)) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLContext context = getSSLContext();
                SSLEngine engine = context.createSSLEngine();
                SSLParameters defaults = context.getDefaultSSLParameters();
                // Anything below TLS 1.2 is not worth offering.
                defaults.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                parameters.setNeedClientAuth(false);
                parameters.setCipherSuites(engine.getEnabledCipherSuites());
                parameters.setSSLParameters(defaults);
            }
        });
        return server;
    }

    private static SSLContext sslContext(EngineConfiguration configuration) throws IOException {
        Path keystorePath = Path.of(configuration.tlsKeystorePath());
        if (!Files.exists(keystorePath)) {
            throw new IOException("TLS keystore not found: " + keystorePath.toAbsolutePath());
        }
        char[] password = configuration.tlsKeystorePassword() == null
                ? new char[0]
                : configuration.tlsKeystorePassword().toCharArray();
        try (InputStream keystoreStream = Files.newInputStream(keystorePath)) {
            KeyStore keystore = KeyStore.getInstance(configuration.tlsKeystoreType());
            keystore.load(keystoreStream, password);

            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keystore, password);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to load the TLS keystore", exception);
        }
    }
}
