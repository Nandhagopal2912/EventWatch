package com.main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates a throwaway self-signed PKCS12 keystore for the TLS tests.
 *
 * Java 17 has no public API for issuing a certificate, and pulling in BouncyCastle for a test
 * fixture is not worth it, so this shells out to the keytool that ships with the running JDK.
 */
final class TestKeystore {
    private TestKeystore() {
    }

    static void generate(Path keystore, String password) throws IOException, InterruptedException {
        Files.createDirectories(keystore.getParent());
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        if (!Files.exists(keytool)) {
            throw new IOException("keytool not found next to the running JDK: " + keytool);
        }

        List<String> command = List.of(
                keytool.toString(),
                "-genkeypair",
                "-alias", "eventwatch",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=localhost,OU=EventWatch,O=EventWatch,L=Test,ST=Test,C=GB",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12",
                "-keystore", keystore.toString(),
                "-storepass", password,
                "-keypass", password);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("keytool timed out generating the test keystore");
        }
        if (process.exitValue() != 0) {
            throw new IOException("keytool failed (" + process.exitValue() + "): " + output);
        }
    }
}
