package com.gradetrack.common;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordHasher {

    private PasswordHasher() {
    }

    public static String hash(String plaintextPassword) {
        return BCrypt.hashpw(plaintextPassword, BCrypt.gensalt());
    }

    public static boolean matches(String plaintextPassword, String hash) {
        return BCrypt.checkpw(plaintextPassword, hash);
    }
}
