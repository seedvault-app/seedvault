package com.stevesoltys.backup.security;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

/**
 * A utility class for encrypting and decrypting data using a {@link Cipher}.
 *
 * @author Steve Soltys
 */
public class CipherUtil {

    /**
     * The cipher algorithm.
     */
    public static final String CIPHER_ALGORITHM = "AES/CFB/PKCS5Padding";

    /**
     * Encrypts the given payload using a key generated from the provided password and salt.
     *
     * @param payload  The payload.
     * @param password The password.
     * @param salt     The salt.
     */
    public static byte[] encrypt(byte[] payload, String password, byte[] salt) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidKeySpecException, BadPaddingException, IllegalBlockSizeException,
            InvalidAlgorithmParameterException, InvalidKeyException {

        SecretKey secretKey = KeyGenerator.generate(password, salt);
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(salt));

        return cipher.doFinal(payload);
    }

    /**
     * Decrypts the given payload using a key generated from the provided password and salt.
     *
     * @param payload  The payload.
     * @param password The password.
     * @param salt     The salt.
     */
    public static byte[] decrypt(byte[] payload, String password, byte[] salt) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidKeySpecException, BadPaddingException, IllegalBlockSizeException,
            InvalidAlgorithmParameterException, InvalidKeyException {

        SecretKey secretKey = KeyGenerator.generate(password, salt);
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(salt));

        return cipher.doFinal(payload);
    }
}
