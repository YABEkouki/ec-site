package com.example.ecsite.mapper;

import com.example.ecsite.entity.Product;
import com.example.ecsite.form.ProductForm;

public class ProductMapper {

    /**
     * Entity → Form
     */
    public static ProductForm toForm(Product product) {

        ProductForm form = new ProductForm();

        form.setName(product.getName());
        form.setPrice(product.getPrice());
        form.setStock(product.getStock());
        form.setDescription(product.getDescription());
        form.setCategoryId(product.getCategory().getId());

        return form;
    }

    /**
     * Form → Entity
     */
    public static Product toEntity(ProductForm form) {

        Product product = new Product();

        product.setName(form.getName());
        product.setPrice(form.getPrice());
        product.setStock(form.getStock());
        product.setDescription(form.getDescription());

        return product;
    }

    public static void copyToEntity(ProductForm form, Product product) {

        product.setName(form.getName());
        product.setPrice(form.getPrice());
        product.setStock(form.getStock());
        product.setDescription(form.getDescription());
    }
}
