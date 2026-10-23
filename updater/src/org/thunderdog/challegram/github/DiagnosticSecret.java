package org.thunderdog.challegram.github;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-keystore encryption; ciphertext/config never enters Android backup or public releases. */
final class DiagnosticSecret {
  private static final String ALIAS = "tgx_private_diagnostic_inbox_v1";
  private static final byte[] AAD = "org.thunderdog.challegram/diagnostic-inbox/v1".getBytes(StandardCharsets.UTF_8);
  private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getNoBackupFilesDir(), "diagnostic-inbox.bin")); }
  private static SecretKey key(boolean create) throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (!store.containsAlias(ALIAS)) {
      if (!create) throw new IllegalStateException("Pair again on this phone.");
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256).setRandomizedEncryptionRequired(true).build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }
  static void save(Context context, String value) throws Exception {
    byte[] encrypted = encrypt(key(true), value.getBytes(StandardCharsets.UTF_8));
    AtomicFile file = file(context); FileOutputStream out = null;
    try { out = file.startWrite(); out.write(encrypted); file.finishWrite(out); }
    catch (Exception e) { file.failWrite(out); throw e; }
  }
  static String load(Context context) throws Exception {
    AtomicFile file = file(context);
    if (!file.getBaseFile().exists()) return null;
    if (file.getBaseFile().length() > 4096) throw new IllegalStateException("Invalid pairing data.");
    return new String(decrypt(key(false), file.readFully()), StandardCharsets.UTF_8);
  }
  static void delete(Context context) throws Exception {
    file(context).delete();
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(ALIAS);
  }
  static byte[] encrypt(SecretKey key, byte[] clear) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(AAD);
    byte[] iv = cipher.getIV(), body = cipher.doFinal(clear);
    if (iv.length != 12) throw new IllegalStateException("Unexpected encryption IV.");
    byte[] out = new byte[12 + body.length]; System.arraycopy(iv,0,out,0,12); System.arraycopy(body,0,out,12,body.length); return out;
  }
  static byte[] decrypt(SecretKey key, byte[] bytes) throws Exception {
    if (bytes.length < 28 || bytes.length > 4096) throw new IllegalArgumentException("Invalid encrypted pairing.");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(bytes,12))); cipher.updateAAD(AAD);
    return cipher.doFinal(bytes,12,bytes.length-12);
  }
}
