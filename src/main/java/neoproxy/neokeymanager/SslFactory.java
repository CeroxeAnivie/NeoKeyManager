package neoproxy.neokeymanager;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

public class SslFactory {

    static {
        // 注册 Bouncy Castle 提供者
        Security.addProvider(new BouncyCastleProvider());
    }

    public static SSLContext createSSLContext(String crtPath, String keyPath) throws Exception {
        // 1. 读取私钥 (支持 PKCS#1 和 PKCS#8)
        PrivateKey privateKey = loadPrivateKey(keyPath);

        // 2. 读取证书链 (支持 Nginx bundle)
        Certificate[] chain = loadCertificates(crtPath);

        if (chain.length == 0) {
            throw new Exception("No certificates found in " + crtPath);
        }

        // 3. 构建内存 KeyStore
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        // 密码设为空，仅在内存中使用
        keyStore.setKeyEntry("neokey-ssl", privateKey, null, chain);

        // 4. 初始化 SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, null);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

    private static PrivateKey loadPrivateKey(String path) throws Exception {
        try (PEMParser parser = new PEMParser(new FileReader(path))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");

            if (object instanceof PEMKeyPair) {
                // PKCS#1 (Nginx 默认格式: -----BEGIN RSA PRIVATE KEY-----)
                return converter.getKeyPair((PEMKeyPair) object).getPrivate();
            } else if (object instanceof PrivateKeyInfo) {
                // PKCS#8 (Java 标准格式: -----BEGIN PRIVATE KEY-----)
                return converter.getPrivateKey((PrivateKeyInfo) object);
            } else {
                throw new Exception("Unknown private key format: " + object.getClass().getName());
            }
        }
    }

    private static Certificate[] loadCertificates(String path) throws Exception {
        List<Certificate> certs = new ArrayList<>();
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");

        try (PEMParser parser = new PEMParser(new FileReader(path))) {
            Object obj;
            while ((obj = parser.readObject()) != null) {
                if (obj instanceof X509CertificateHolder) {
                    certs.add(converter.getCertificate((X509CertificateHolder) obj));
                }
            }
        }
        return certs.toArray(new Certificate[0]);
    }
}