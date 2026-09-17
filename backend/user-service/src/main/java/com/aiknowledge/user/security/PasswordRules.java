package com.aiknowledge.user.security;

/** The password rules shared by registration, password changes and resets. */
public final class PasswordRules {
    private PasswordRules() {
    }

    /** Null when the password is acceptable, otherwise the reason it is not. */
    public static String problem(String password, String username) {
        if (password == null || password.length() < 8 || password.length() > 128) return "password must contain between 8 and 128 characters";
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) return "password must contain letters and numbers";
        if (password.chars().anyMatch(Character::isWhitespace)) return "password must not contain whitespace";
        if (username != null && password.equalsIgnoreCase(username)) return "password must differ from username";
        return null;
    }
}
