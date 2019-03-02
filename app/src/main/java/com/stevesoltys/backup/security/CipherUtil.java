package com.stevesoltys.backup.security;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

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

    /**.
     * Encrypts the given payload using the provided secret key.
     *
     * @param payload  The payload.
     * @param secretKey The secret key.
     * @param iv        The initialization vector.
     */
    public static byte[] encrypt(byte[] payload, SecretKey secretKey, byte[] iv) throws NoSuchPaddingException,
            NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException,
            InvalidAlgorithmParameterException, InvalidKeyException {

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
        return cipher.doFinal(payload);
    }

    /**
     * Decrypts the given payload using the provided secret key.
     *
     * @param payload   The payload.
     * @param secretKey The secret key.
     * @param iv        The initialization vector.
     */
    public static byte[] decrypt(byte[] payload, SecretKey secretKey, byte[] iv) throws NoSuchPaddingException,
            NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException,
            InvalidAlgorithmParameterException, InvalidKeyException {

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        return cipher.doFinal(payload);
    }
}
