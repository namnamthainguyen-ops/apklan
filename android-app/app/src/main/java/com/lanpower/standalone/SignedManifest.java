package com.lanpower.standalone;

import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Verifies the exact signed payload bytes before interpreting any release metadata. */
final class SignedManifest {
    static final String PACKAGE = "com.lanpower.standalone";
    static final String SIGNER = "253e62e567d191b4481aaae2cd087610e1ca730b76e5cf19ec4e97165fa22b5a";
    static final String MANIFEST_URL = "https://raw.githubusercontent.com/namnamthainguyen-ops/apklan/main/updates/stable.json";
    private static final String URL_PREFIX = "/namnamthainguyen-ops/apklan/";
    final String version;
    final int versionCode;
    final String url;
    final String sha256;
    final String signerSha256;
    final String releaseNotes;

    private SignedManifest(JSONObject android, String notes) throws Exception {
        version = string(android, "version");
        Object rawCode = android.get("versionCode");
        if (!(rawCode instanceof Number) || rawCode instanceof Double || rawCode instanceof Float) throw new SecurityException("Version code không hợp lệ");
        long code = ((Number) rawCode).longValue();
        if (code < 1 || code > Integer.MAX_VALUE) throw new SecurityException("Version code ngoài phạm vi");
        versionCode = (int) code;
        url = string(android, "url");
        validateUrl(url);
        sha256 = string(android, "sha256").toLowerCase(java.util.Locale.ROOT);
        signerSha256 = string(android, "signerSha256").toLowerCase(java.util.Locale.ROOT);
        if (!sha256.matches("[a-f0-9]{64}") || !SIGNER.equals(signerSha256)) throw new SecurityException("Checksum/chứng thư cập nhật không hợp lệ");
        if (!PACKAGE.equals(string(android, "packageName"))) throw new SecurityException("Sai package cập nhật");
        if (version.isEmpty() || version.length() > 80) throw new SecurityException("Version không hợp lệ");
        releaseNotes = notes.length() > 8000 ? notes.substring(0, 8000) : notes;
    }

    static SignedManifest verify(String envelopeJson, PublicKey key, int installedCode, int highestSeenCode) throws Exception {
        if (envelopeJson.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw new SecurityException("Manifest vượt giới hạn");
        JSONObject envelope = new JSONObject(envelopeJson);
        if (!"RS256".equals(string(envelope, "algorithm"))) throw new SecurityException("Thuật toán cập nhật không hợp lệ");
        byte[] payload = Base64.getDecoder().decode(string(envelope, "payload"));
        byte[] signed = Base64.getDecoder().decode(string(envelope, "signature"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(payload);
        if (!verifier.verify(signed)) throw new SecurityException("Chữ ký manifest không hợp lệ");
        JSONObject document = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (!Integer.valueOf(1).equals(document.get("schemaVersion")) || !"stable".equals(string(document, "channel"))) throw new SecurityException("Schema/channel không được hỗ trợ");
        SignedManifest result = new SignedManifest(document.getJSONObject("android"), document.optString("releaseNotes", ""));
        if (result.versionCode < Math.max(installedCode, highestSeenCode)) throw new SecurityException("Từ chối bản cập nhật cũ hơn phiên bản đã tin cậy");
        return result;
    }

    static PublicKey publicKey(String modulusBase64, String exponentBase64) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getDecoder().decode(modulusBase64));
        BigInteger exponent = new BigInteger(1, Base64.getDecoder().decode(exponentBase64));
        if (modulus.bitLength() < 3072 || exponent.compareTo(BigInteger.valueOf(65537)) != 0) throw new SecurityException("Khóa cập nhật không hợp lệ");
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    static void validateUrl(String value) throws Exception {
        URI uri = new URI(value);
        String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme()) || !"raw.githubusercontent.com".equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null || path == null || !path.startsWith(URL_PREFIX)
                || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0 || path.contains("/../") || path.contains("/./") || !uri.normalize().equals(uri)) {
            throw new SecurityException("URL cập nhật ngoài kho được phép");
        }
    }

    private static String string(JSONObject value, String name) throws Exception {
        Object item = value.get(name);
        if (!(item instanceof String)) throw new SecurityException("Trường manifest không hợp lệ: " + name);
        return (String) item;
    }
}
