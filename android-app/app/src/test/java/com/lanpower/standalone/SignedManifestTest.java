package com.lanpower.standalone;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import static org.junit.Assert.*;

public final class SignedManifestTest {
    private static KeyPair keys;
    @BeforeClass public static void createTestKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        keys = generator.generateKeyPair();
    }
    private static JSONObject document(int code) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("channel", "stable").put("publishedUtc", "2026-11-06T00:00:00Z")
                .put("android", new JSONObject().put("version", "1.2.0").put("versionCode", code)
                        .put("url", "https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/0123456789012345678901234567890123456789/release/RoomTN20.apk")
                        .put("sha256", "a".repeat(64)).put("packageName", SignedManifest.PACKAGE).put("signerSha256", SignedManifest.SIGNER))
                .put("releaseNotes", "Test release");
    }
    private static String envelope(JSONObject document) throws Exception {
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate()); signature.update(bytes);
        return new JSONObject().put("algorithm", "RS256").put("payload", Base64.getEncoder().encodeToString(bytes))
                .put("signature", Base64.getEncoder().encodeToString(signature.sign())).toString();
    }
    @Test public void verifiesPayloadBeforeReadingReleaseAndAllowsCurrentVersion() throws Exception {
        SignedManifest result = SignedManifest.verify(envelope(document(5)), keys.getPublic(), 4, 4);
        assertEquals(5, result.versionCode);
        assertEquals("1.2.0", result.version);
        assertEquals("Test release", result.releaseNotes);
        assertEquals(5, SignedManifest.verify(envelope(document(5)), keys.getPublic(), 5, 5).versionCode);
    }
    @Test public void rejectsTamperingWrongTrustAnchorAndAlgorithm() throws Exception {
        String valid = envelope(document(5));
        JSONObject tampered = new JSONObject(valid).put("payload", Base64.getEncoder().encodeToString(document(6).toString().getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> SignedManifest.verify(tampered.toString(), keys.getPublic(), 4, 4));
        assertThrows(Exception.class, () -> SignedManifest.verify(valid, UpdateTrust.key(), 4, 4));
        assertThrows(Exception.class, () -> SignedManifest.verify(new JSONObject(valid).put("algorithm", "none").toString(), keys.getPublic(), 4, 4));
        assertThrows(Exception.class, () -> SignedManifest.verify(new JSONObject(valid).put("signature", "AAAA").toString(), keys.getPublic(), 4, 4));
    }
    @Test public void rejectsRollbackWrongPackageSignerChannelAndSchemaEvenWhenSigned() throws Exception {
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(document(4)), keys.getPublic(), 5, 5));
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(document(5)), keys.getPublic(), 4, 6));
        JSONObject wrongPackage = document(5); wrongPackage.getJSONObject("android").put("packageName", "other.package");
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(wrongPackage), keys.getPublic(), 4, 4));
        JSONObject wrongSigner = document(5); wrongSigner.getJSONObject("android").put("signerSha256", "b".repeat(64));
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(wrongSigner), keys.getPublic(), 4, 4));
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(document(5).put("channel", "test")), keys.getPublic(), 4, 4));
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(document(5).put("schemaVersion", 2)), keys.getPublic(), 4, 4));
    }
    @Test public void urlPolicyRejectsWrongHostRepoSchemeCredentialsEncodedPathsAndTraversal() throws Exception {
        SignedManifest.validateUrl(SignedManifest.MANIFEST_URL);
        for (String bad : new String[]{"http://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/a.apk", "https://evil.example/namnamthainguyen-ops/apklan/a.apk", "https://raw.githubusercontent.com/other/apklan/a.apk", "https://raw.githubusercontent.com/namnamthainguyen-ops/apklan-evil/a.apk", "https://user@raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/a.apk", "https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/../other/a.apk", "https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/%2e%2e/a.apk", SignedManifest.MANIFEST_URL + "?next=evil", SignedManifest.MANIFEST_URL + "#x", "https://raw.githubusercontent.com:443/namnamthainguyen-ops/apklan/main/a.apk"}) {
            assertThrows(bad, Exception.class, () -> SignedManifest.validateUrl(bad));
        }
        JSONObject bad = document(5); bad.getJSONObject("android").put("url", "https://evil.example/a.apk");
        assertThrows(Exception.class, () -> SignedManifest.verify(envelope(bad), keys.getPublic(), 4, 4));
    }
    @Test public void publicKeyRequires3072BitsAndStandardExponent() throws Exception {
        RSAPublicKey rsa = (RSAPublicKey) keys.getPublic();
        String modulus = Base64.getEncoder().encodeToString(rsa.getModulus().toByteArray());
        assertEquals(rsa, SignedManifest.publicKey(modulus, "AQAB"));
        assertThrows(Exception.class, () -> SignedManifest.publicKey("AQAB", "AQAB"));
        assertThrows(Exception.class, () -> SignedManifest.publicKey(modulus, "Aw=="));
        assertEquals(3072, ((RSAPublicKey) UpdateTrust.key()).getModulus().bitLength());
    }
}
