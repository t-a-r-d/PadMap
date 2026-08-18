package com.slickstax841.padmap.inject;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * On-device wireless ADB connection. Pairing keys live in the app files dir.
 * This is the same privilege path ShootingPlus uses, without their jar or Shizuku.
 */
public final class PadMapAdbManager extends AbsAdbConnectionManager {

    private static PadMapAdbManager instance;

    public static synchronized PadMapAdbManager get(Context context) throws Exception {
        if (instance == null) instance = new PadMapAdbManager(context.getApplicationContext());
        return instance;
    }

    private final File keyFile;
    private final File certFile;
    private PrivateKey privateKey;
    private Certificate certificate;

    private PadMapAdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File dir = new File(context.getFilesDir(), "adb");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create adb key dir");
        }
        keyFile = new File(dir, "pkcs8.key");
        certFile = new File(dir, "cert.crt");
        if (keyFile.exists() && certFile.exists()) {
            load();
        } else {
            generate();
            save();
        }
    }

    private void load() throws Exception {
        byte[] keyBytes = readAll(keyFile);
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        try (FileInputStream in = new FileInputStream(certFile)) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private void generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair pair = kpg.generateKeyPair();
        PublicKey publicKey = pair.getPublic();
        privateKey = pair.getPrivate();

        String subject = "CN=PadMap";
        String algorithmName = "SHA512withRSA";
        long expiry = System.currentTimeMillis() + 86400000L * 365 * 10;
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        X500Name name = new X500Name(subject);
        Date notBefore = new Date();
        Date notAfter = new Date(expiry);
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        CertificateValidity validity = new CertificateValidity(notBefore, notAfter);
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", validity);
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithmName);
        certificate = cert;
    }

    private void save() throws Exception {
        try (FileOutputStream out = new FileOutputStream(keyFile)) {
            out.write(privateKey.getEncoded());
        }
        try (FileOutputStream out = new FileOutputStream(certFile)) {
            out.write(certificate.getEncoded());
        }
    }

    private static byte[] readAll(File file) throws Exception {
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
        }
        return buf;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "PadMap";
    }
}
