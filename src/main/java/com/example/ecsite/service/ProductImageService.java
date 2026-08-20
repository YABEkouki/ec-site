package com.example.ecsite.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.ecsite.exception.InvalidProductImageException;

@Service
public class ProductImageService {

    private final Path productImageDir;

    public ProductImageService(
            @Value("${app.upload.product-image-dir}") String productImageDir) {

        this.productImageDir = Path.of(productImageDir)
                .toAbsolutePath()
                .normalize();
    }

    public String saveImage(Long productId, MultipartFile imageFile) {

        if (imageFile == null || imageFile.isEmpty()) {
            return null;
        }

        validateImage(imageFile);

        String originalFilename = imageFile.getOriginalFilename();
        String extension = getExtension(originalFilename);

        String filename = UUID.randomUUID() + extension;

        Path productDir = productImageDir.resolve(productId.toString());

        try {
            Files.createDirectories(productDir);

            Path destination = productDir.resolve(filename);

            Files.copy(
                    imageFile.getInputStream(),
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);

            return productId + "/" + filename;

        } catch (IOException e) {
            throw new IllegalStateException("商品画像の保存に失敗しました。", e);
        }
    }

    private String getExtension(String filename) {

        if (filename == null) {
            return "";
        }

        int dotIndex = filename.lastIndexOf('.');

        if (dotIndex < 0) {
            return "";
        }

        return filename.substring(dotIndex).toLowerCase();
    }

    private void validateImage(MultipartFile imageFile) {

        String contentType = imageFile.getContentType();
        String extension = getExtension(imageFile.getOriginalFilename());

        boolean validContentType = "image/jpeg".equals(contentType)
                || "image/png".equals(contentType)
                || "image/webp".equals(contentType);

        boolean validExtension = ".jpg".equals(extension)
                || ".jpeg".equals(extension)
                || ".png".equals(extension)
                || ".webp".equals(extension);

        if (!validContentType || !validExtension) {
            throw new InvalidProductImageException(
                    "商品画像はJPEG、PNG、WebP形式を指定してください。");
        }
    }

    public void deleteImage(String imagePath) {

        if (imagePath == null || imagePath.isBlank()) {
            return;
        }

        Path target = productImageDir.resolve(imagePath).normalize();

        if (!target.startsWith(productImageDir)) {
            throw new IllegalArgumentException("不正な画像パスです。");
        }

        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("商品画像の削除に失敗しました。", e);
        }
    }
}