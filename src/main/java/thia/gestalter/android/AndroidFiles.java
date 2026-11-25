package thia.gestalter.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import thia.gestalter.core.*;

final class AndroidFiles {
    private AndroidFiles() {}

    static void syncDirectory(Path directory) throws IOException {
        try {
            FileDescriptor handle = Os.open(directory.toString(), OsConstants.O_RDONLY, 0);
            try { Os.fsync(handle); }
            finally { Os.close(handle); }
        } catch (ErrnoException error) {
            throw new IOException("Could not commit app storage.", error);
        }
    }

    static final class Vault implements Profile.Vault {
        private static final String ALIAS = "gestalter-profile-v1";
        private final DurableFiles files;
        private final Path path;

        Vault(DurableFiles files, Path path) {
            this.files = files;
            this.path = path;
        }

        private SecretKey key(boolean create) throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
            if (existing != null) return existing;
            if (!create) throw new IOException("Android could not unlock this workspace.");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }

        public byte[] read() throws Exception {
            if (!Files.exists(path)) return null;
            byte[] envelope = Files.readAllBytes(path);
            if (envelope.length < 29 || envelope[0] != 1) throw new IOException("Invalid workspace unlock data.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, envelope, 1, 12));
            return cipher.doFinal(envelope, 13, envelope.length - 13);
        }

        public void write(byte[] state) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(state);
            byte[] envelope = Arrays.copyOf(new byte[]{1}, 13 + encrypted.length);
            System.arraycopy(cipher.getIV(), 0, envelope, 1, 12);
            System.arraycopy(encrypted, 0, envelope, 13, encrypted.length);
            files.write(path, envelope);
        }
    }
}
