package com.stevesoltys.backup.security;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;

/**
 * A utility class which can be used for generating an AES secret key using PBKDF2.
 *
 * @author Steve Soltys
 */
public class KeyGenerator {

    /**
     * The number of iterations for key generation.
     */
    private static final int ITERATIONS = 100;

    /**
     * The generated key length.
     */
    private static final int KEY_LENGTH = 256;

    /**
     * Generates an AES secret key using PBKDF2.
     *
     * @param password The password.
     * @param salt     The salt.
     * @return The generated key.
     */
    public static SecretKey generate(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {

        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        KeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);

        SecretKey secretKey = secretKeyFactory.generateSecret(keySpec);
        return new SecretKeySpec(secretKey.getEncoded(), "AES");
    }
}
