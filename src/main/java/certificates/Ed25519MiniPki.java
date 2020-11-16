package certificates;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import sun.security.pkcs10.PKCS10;
import sun.security.x509.AlgorithmId;
import sun.security.x509.AuthorityKeyIdentifierExtension;
import sun.security.x509.BasicConstraintsExtension;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateExtensions;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.DNSName;
import sun.security.x509.GeneralName;
import sun.security.x509.GeneralNames;
import sun.security.x509.KeyIdentifier;
import sun.security.x509.KeyUsageExtension;
import sun.security.x509.SubjectAlternativeNameExtension;
import sun.security.x509.SubjectKeyIdentifierExtension;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

public final class Ed25519MiniPki {

    // Ed25519 uses just "Ed25519" as the JCA signature algorithm name.
    private static final String SIG_ALG = "Ed25519";

    // Defaults (override via args if you want)
    private static final int ROOT_VALIDITY_DAYS = 3650;  // 10y
    private static final int INT_VALIDITY_DAYS  = 1825;  // 5y
    private static final int LEAF_VALIDITY_DAYS =  825;  // ~27m

    public static void main(String[] args) throws Exception {
        // You can pass:
        // 0: root DN
        // 1: intermediate DN
        // 2: leaf DN
        // 3: leaf DNS SAN (optional, e.g. "example.internal")
        // 4: password for PKCS12 files (optional)
        String rootDn = args.length > 0 ? args[0] : "CN=Example Root CA, O=Example Org, C=DE";
        String intDn  = args.length > 1 ? args[1] : "CN=Example Intermediate CA, O=Example Org, C=DE";
        String leafDn = args.length > 2 ? args[2] : "CN=leaf.example.internal, O=Example Org, C=DE";
        String leafDnsSan = args.length > 3 ? args[3] : "leaf.example.internal";
        char[] p12Pass = (args.length > 4 ? args[4] : "changeit").toCharArray();

        // 1) Root CA
        KeyPair rootKp = generateEd25519KeyPair();
        X509Certificate rootCert = createSelfSignedCaCert(
                rootKp,
                new X500Name(rootDn),
                ROOT_VALIDITY_DAYS,
                /*pathLen*/ 1
        );

        // 2) Intermediate CA signed by root
        KeyPair intKp = generateEd25519KeyPair();
        X509Certificate intCert = createIssuedCaCert(
                intKp.getPublic(),
                new X500Name(intDn),
                rootKp.getPrivate(),
                rootCert,
                INT_VALIDITY_DAYS,
                /*pathLen*/ 0
        );

        // 3) Leaf keypair + CSR
        KeyPair leafKp = generateEd25519KeyPair();
        X500Name leafName = new X500Name(leafDn);

        PKCS10 csr = createCsr(leafKp, leafName);
        byte[] csrDer = csr.getEncoded();

        // 4) Sign CSR with intermediate -> leaf cert
        //    Use subject/public key from CSR (since we just created it)
        X509Certificate leafCert = createIssuedLeafCert(
                leafKp.getPublic(),
                leafName,
                intKp.getPrivate(),
                intCert,
                LEAF_VALIDITY_DAYS,
                leafDnsSan
        );

        // ---- Output ----
        writePem("root-ca.crt.pem", "CERTIFICATE", rootCert.getEncoded());
        writePem("intermediate-ca.crt.pem", "CERTIFICATE", intCert.getEncoded());
        writePem("leaf.csr.pem", "CERTIFICATE REQUEST", csrDer);
        writePem("leaf.crt.pem", "CERTIFICATE", leafCert.getEncoded());

        // Private keys (UNENCRYPTED PKCS#8 PEM) — protect these files!
        writePem("root-ca.key.pkcs8.pem", "PRIVATE KEY", rootKp.getPrivate().getEncoded());
        writePem("intermediate-ca.key.pkcs8.pem", "PRIVATE KEY", intKp.getPrivate().getEncoded());
        writePem("leaf.key.pkcs8.pem", "PRIVATE KEY", leafKp.getPrivate().getEncoded());

        // Chain file: leaf -> intermediate -> root
        writePemChain("leaf.chain.pem", leafCert, intCert, rootCert);

        // PKCS#12 keystores for convenience
        writePkcs12("root-ca.p12", rootCert, rootKp.getPrivate(), p12Pass, new Certificate[]{rootCert});
        writePkcs12("intermediate-ca.p12", intCert, intKp.getPrivate(), p12Pass, new Certificate[]{intCert, rootCert});
        writePkcs12("leaf.p12", leafCert, leafKp.getPrivate(), p12Pass, new Certificate[]{leafCert, intCert, rootCert});

        System.out.println("Wrote PEM:");
        System.out.println("  root-ca.crt.pem");
        System.out.println("  intermediate-ca.crt.pem");
        System.out.println("  leaf.csr.pem");
        System.out.println("  leaf.crt.pem");
        System.out.println("  leaf.chain.pem");
        System.out.println("Wrote keys (UNENCRYPTED PKCS#8 PEM) + .p12 files");
    }

    // ---------- Key generation ----------

    private static KeyPair generateEd25519KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        // Ed25519 is deterministic per keygen; still use a strong RNG for key generation entropy.
        SecureRandom rng = SecureRandom.getInstanceStrong();
        kpg.initialize(255, rng); // Some providers ignore this; harmless if ignored.
        return kpg.generateKeyPair();
    }

    // ---------- CSR ----------

    private static PKCS10 createCsr(KeyPair leafKp, X500Name subject) throws Exception {
        PKCS10 req = new PKCS10(leafKp.getPublic());
        // Encodes and signs the request (PKCS#10)
        req.encodeAndSign(subject, leafKp.getPrivate(), SIG_ALG);
        return req;
    }

    // ---------- Certificates ----------

    private static X509Certificate createSelfSignedCaCert(
            KeyPair caKeyPair,
            X500Name subjectAndIssuer,
            int validityDays,
            int pathLen
    ) throws Exception {
        return buildAndSignCert(
                caKeyPair.getPublic(),
                subjectAndIssuer,
                subjectAndIssuer,
                caKeyPair.getPrivate(),
                /*issuerCertForAki*/ null,
                validityDays,
                /*isCa*/ true,
                pathLen,
                /*leafDnsSan*/ null
        );
    }

    private static X509Certificate createIssuedCaCert(
            java.security.PublicKey subjectPublicKey,
            X500Name subject,
            PrivateKey issuerPrivateKey,
            X509Certificate issuerCert,
            int validityDays,
            int pathLen
    ) throws Exception {
        X500Name issuer = new X500Name(issuerCert.getSubjectX500Principal().getName());
        return buildAndSignCert(
                subjectPublicKey,
                subject,
                issuer,
                issuerPrivateKey,
                issuerCert,
                validityDays,
                /*isCa*/ true,
                pathLen,
                /*leafDnsSan*/ null
        );
    }

    private static X509Certificate createIssuedLeafCert(
            java.security.PublicKey subjectPublicKey,
            X500Name subject,
            PrivateKey issuerPrivateKey,
            X509Certificate issuerCert,
            int validityDays,
            String dnsSan
    ) throws Exception {
        X500Name issuer = new X500Name(issuerCert.getSubjectX500Principal().getName());
        return buildAndSignCert(
                subjectPublicKey,
                subject,
                issuer,
                issuerPrivateKey,
                issuerCert,
                validityDays,
                /*isCa*/ false,
                /*pathLen*/ -1,
                dnsSan
        );
    }

    private static X509Certificate buildAndSignCert(
            java.security.PublicKey subjectPublicKey,
            X500Name subject,
            X500Name issuer,
            PrivateKey issuerPrivateKey,
            X509Certificate issuerCertForAki,
            int validityDays,
            boolean isCa,
            int pathLen,
            String leafDnsSan
    ) throws Exception {

        // Backdate 24h to reduce "not yet valid" from clock skew.
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 60 * 60 * 1000);
        Date notAfter  = new Date(notBefore.getTime() + validityDays * 24L * 60 * 60 * 1000);

        SecureRandom rng = SecureRandom.getInstanceStrong();
        BigInteger serial = new BigInteger(160, rng).abs();

        X509CertInfo info = new X509CertInfo();
        info.setVersion(new CertificateVersion(CertificateVersion.V3));
        info.setSerialNumber(new CertificateSerialNumber(serial));
        info.setSubject(subject);
        info.setIssuer(issuer);
        info.setValidity(new CertificateValidity(notBefore, notAfter));
        info.setKey(new CertificateX509Key(subjectPublicKey));
        info.setAlgorithmId(new CertificateAlgorithmId(AlgorithmId.get(SIG_ALG)));

        CertificateExtensions exts = new CertificateExtensions();

        // KeyUsage
        // bits: 0 digitalSignature, 5 keyCertSign, 6 cRLSign
        boolean[] ku = new boolean[9];
        if (isCa) {
            ku[5] = true; // keyCertSign
            ku[6] = true; // cRLSign
        } else {
            ku[0] = true; // digitalSignature (typical for Ed25519 leaf, e.g. TLS)
        }
        exts.setExtension(KeyUsageExtension.NAME, new KeyUsageExtension(ku));

        // BasicConstraints
        if (isCa) {
            // critical = true for CA certs is common
            exts.setExtension(BasicConstraintsExtension.NAME,
                    new BasicConstraintsExtension(Boolean.TRUE, true, pathLen));
        } else {
            // critical basic constraints with CA=false
            exts.setExtension(BasicConstraintsExtension.NAME,
                    new BasicConstraintsExtension(Boolean.TRUE, false, -1));
        }

        // SKI
        KeyIdentifier subjectKid = new KeyIdentifier(subjectPublicKey);
        exts.setExtension(SubjectKeyIdentifierExtension.NAME,
                new SubjectKeyIdentifierExtension(subjectKid.getIdentifier()));

        // AKI (for issued certs)
        if (issuerCertForAki != null) {
            KeyIdentifier issuerKid = new KeyIdentifier(issuerCertForAki.getPublicKey());
            exts.setExtension(AuthorityKeyIdentifierExtension.NAME,
                    new AuthorityKeyIdentifierExtension(issuerKid, null, null));
        } else {
            // self-signed: AKI usually matches SKI
            exts.setExtension(AuthorityKeyIdentifierExtension.NAME,
                    new AuthorityKeyIdentifierExtension(subjectKid, null, null));
        }

        // Optional SAN for leaf
        if (!isCa && leafDnsSan != null && !leafDnsSan.isBlank()) {
            GeneralNames gns = new GeneralNames();
            gns.add(new GeneralName(new DNSName(leafDnsSan)));
            // non-critical SAN
            exts.setExtension(SubjectAlternativeNameExtension.NAME,
                    new SubjectAlternativeNameExtension(false, gns));
        }

        info.setExtensions(exts);

        X509CertImpl cert = X509CertImpl.newSigned(info, issuerPrivateKey, SIG_ALG);

//        AlgorithmId actualAlgId = cert.getSigAlg();
//        info.setAlgorithmId(CertificateAlgorithmId.NAME + "." + actualAlgId.getName().ALGORITHM, actualAlgId);


        cert = X509CertImpl.newSigned(info, issuerPrivateKey, SIG_ALG);
        return cert;
    }

    // ---------- Output helpers ----------

    private static void writePkcs12(
            String filename,
            X509Certificate cert,
            PrivateKey privateKey,
            char[] password,
            Certificate[] chain
    ) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("key", privateKey, password, chain);
        try (OutputStream os = new FileOutputStream(filename)) {
            ks.store(os, password);
        }
    }

    private static void writePem(String filename, String type, byte[] derBytes) throws Exception {
        Base64.Encoder enc = Base64.getMimeEncoder(64, new byte[]{'\n'});
        String b64 = enc.encodeToString(derBytes);
        String pem = "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
        try (OutputStream os = new FileOutputStream(filename)) {
            os.write(pem.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void writePemChain(String filename, X509Certificate... certs) throws Exception {
        try (OutputStream os = new FileOutputStream(filename)) {
            for (X509Certificate c : certs) {
                os.write(("-----BEGIN CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII));
                os.write(Base64.getMimeEncoder(64, new byte[]{'\n'}).encode(c.getEncoded()));
                os.write(("\n-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII));
            }
        }
    }
}
