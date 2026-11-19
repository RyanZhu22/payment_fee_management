package com.omsweb.fees.operations;

/**
 * Stores generated business documents (notice, reminder and receipt PDFs).
 * Implementations persist the bytes under a key recorded on the fee document row.
 */
public interface DocumentStorage {
    void write(String key, byte[] content);

    byte[] read(String key);
}
