package com.example.mirobotai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores provider API keys encrypted with Android Keystore. */
public class ApiKeyStore {
    private static final String PREFS = "mirobot_ai_secure";
    private static final String ALIAS = "mirobot_ai_key_v2";
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final SharedPreferences prefs;

    public ApiKeyStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String value) throws Exception {
        save("default", value);
    }

    public String load() {
        return load("default");
    }

    public boolean hasKey() {
        return hasKey("default");
    }

    public void clear() {
        clear("default");
    }

    public void save(String slot, String value) throws Exception {
        String safeSlot = normalizeSlot(slot);
        if (value == null || value.trim().isEmpty()) {
            clear(safeSlot);
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString(safeSlot + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(safeSlot + "_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    public String load(String slot) {
        try {
            String safeSlot = normalizeSlot(slot);
            String iv64 = prefs.getString(safeSlot + "_iv", null);
            String data64 = prefs.getString(safeSlot + "_data", null);
            if (iv64 == null || data64 == null) return null;
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(iv64, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(data64, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasKey(String slot) {
        String value = load(slot);
        return value != null && !value.isEmpty();
    }

    public void clear(String slot) {
        String safeSlot = normalizeSlot(slot);
        prefs.edit().remove(safeSlot + "_iv").remove(safeSlot + "_data").apply();
    }

    private String normalizeSlot(String slot) {
        if (slot == null || slot.trim().isEmpty()) return "default";
        return slot.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_");
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
