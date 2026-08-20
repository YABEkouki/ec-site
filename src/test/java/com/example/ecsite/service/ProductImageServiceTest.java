package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.example.ecsite.exception.InvalidProductImageException;

class ProductImageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveImageSavesJpegFile() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        MockMultipartFile imageFile = new MockMultipartFile(
                "imageFile",
                "product.jpg",
                "image/jpeg",
                "test image".getBytes());

        String imagePath = service.saveImage(1L, imageFile);

        Path savedFile = tempDir.resolve(imagePath);

        assertTrue(imagePath.startsWith("1/"));
        assertTrue(imagePath.endsWith(".jpg"));
        assertTrue(Files.exists(savedFile));
    }

    @Test
    void saveImageReturnsNullWhenImageFileIsNull() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        String imagePath = service.saveImage(1L, null);

        assertNull(imagePath);
    }

    @Test
    void saveImageRejectsInvalidContentType() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        MockMultipartFile imageFile = new MockMultipartFile(
                "imageFile",
                "product.txt",
                "text/plain",
                "not an image".getBytes());

        assertThrows(
                InvalidProductImageException.class,
                () -> service.saveImage(1L, imageFile));
    }

    @Test
    void saveImageRejectsInvalidExtension() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        MockMultipartFile imageFile = new MockMultipartFile(
                "imageFile",
                "product.exe",
                "image/jpeg",
                "test image".getBytes());

        assertThrows(
                InvalidProductImageException.class,
                () -> service.saveImage(1L, imageFile));
    }

    @Test
    void deleteImageDeletesSavedFile() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        MockMultipartFile imageFile = new MockMultipartFile(
                "imageFile",
                "product.jpg",
                "image/jpeg",
                "test image".getBytes());

        String imagePath = service.saveImage(1L, imageFile);

        Path savedFile = tempDir.resolve(imagePath);

        assertTrue(Files.exists(savedFile));

        service.deleteImage(imagePath);

        assertTrue(Files.notExists(savedFile));
    }

    @Test
    void deleteImageDoesNothingWhenImagePathIsNull() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        assertDoesNotThrow(
                () -> service.deleteImage(null));
    }

    @Test
    void deleteImageRejectsPathOutsideProductImageDirectory() {

        ProductImageService service = new ProductImageService(tempDir.toString());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.deleteImage("../../outside.jpg"));
    }
}