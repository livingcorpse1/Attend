package com.veriqra.app.service.storage;

import java.util.Optional;

/**
 * Binary storage for biometric artefacts.
 *
 * <p>Enrolment images are the only binary objects the platform keeps. They are written
 * through this abstraction so the deployment can point at Supabase Storage without a
 * code change, while local development keeps working with a directory on disk.
 */
public interface StorageService {

    /**
     * Stores an object.
     *
     * @param path        the object path inside the bucket
     * @param content     the raw bytes
     * @param contentType the media type of the content
     * @return the path the object can be read back with
     */
    String store(String path, byte[] content, String contentType);

    /** Reads an object back. */
    Optional<byte[]> read(String path);

    /** Removes an object. Deleting a missing object is not an error. */
    void delete(String path);

    /** The name of the active provider, exposed for diagnostics. */
    String providerName();
}
