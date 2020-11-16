package server2;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Objects;

public final class SslContexts {

    private SslContexts() {}

    public static SSLContext fromPkcs12(Path p12Path, char[] password) {
        Objects.requireNonNull(p12Path, "p12Path");
        Objects.requireNonNull(password, "password");
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(p12Path)) {
                ks.load(in, password);
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load PKCS12 from " + p12Path, e);
        }
    }
}
