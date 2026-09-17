package com.aiknowledge.user.security;

/** Sends plain-text mail. {@link #available()} is false when no mail server is configured. */
public interface EmailSender {
    boolean available();

    void send(String to, String subject, String body);

    /** For tests and installations without a mail server. */
    EmailSender NONE = new EmailSender() {
        @Override public boolean available() { return false; }
        @Override public void send(String to, String subject, String body) {
            throw new IllegalStateException("no mail server is configured");
        }
    };
}
