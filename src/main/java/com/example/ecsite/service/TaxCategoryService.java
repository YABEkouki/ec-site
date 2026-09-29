package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.TaxCategory;
import com.example.ecsite.repository.TaxCategoryRepository;

@Service
@Transactional(readOnly = true)
public class TaxCategoryService {

    private final TaxCategoryRepository taxCategoryRepository;

    public TaxCategoryService(
            TaxCategoryRepository taxCategoryRepository) {

        this.taxCategoryRepository = taxCategoryRepository;
    }

    public List<TaxCategory> findActiveTaxCategories() {

        return taxCategoryRepository
                .findByActiveTrueOrderByDisplayOrderAscIdAsc();
    }

    public TaxCategory findActiveById(Long id) {

        return taxCategoryRepository
                .findByIdAndActiveTrue(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "有効な税区分が見つかりません。"));
    }
}
