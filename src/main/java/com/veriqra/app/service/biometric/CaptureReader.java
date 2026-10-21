package com.veriqra.app.service.biometric;

import com.veriqra.app.config.AppProperties;
import com.veriqra.app.security.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the frames uploaded by the browser into decodable images.
 *
 * <p>Every frame is size checked and decoded here so that a malformed or oversized
 * upload never reaches the inference pipeline.
 */
@Component
public class CaptureReader {

    private final AppProperties properties;

    public CaptureReader(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Decodes the uploaded frames.
     *
     * @throws ApiException when the upload is empty, oversized or cannot be decoded
     */
    public List<FaceImage> read(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("NO_FRAMES", "No frames were captured. Please try again.");
        }
        int maximum = properties.getBiometric().getRequiredFrames() * 3;
        if (files.size() > maximum) {
            throw ApiException.badRequest("TOO_MANY_FRAMES",
                    "Too many frames were uploaded. Please try again.");
        }

        long limit = properties.getUpload().getMaxImageBytes();
        List<FaceImage> images = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > limit) {
                throw ApiException.badRequest("FRAME_TOO_LARGE",
                        "One of the captured frames is too large. Please try again.");
            }
            try {
                images.add(FaceImage.decode(file.getBytes()));
            } catch (IOException ex) {
                throw ApiException.badRequest("FRAME_UNREADABLE",
                        "One of the captured frames could not be read. Please try again.");
            }
        }
        if (images.isEmpty()) {
            throw ApiException.badRequest("NO_FRAMES", "No frames were captured. Please try again.");
        }
        return images;
    }
}
