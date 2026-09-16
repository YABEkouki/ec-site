package com.example.ecsite.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductSearchKeyword;
import com.example.ecsite.exception.InvalidProductSearchKeywordException;
import com.example.ecsite.repository.ProductSearchKeywordRepository;

@Service
public class ProductSearchKeywordService {

    private static final int MAX_KEYWORD_COUNT = 10;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final ProductSearchKeywordRepository productSearchKeywordRepository;

    public ProductSearchKeywordService(
            ProductSearchKeywordRepository productSearchKeywordRepository) {
        this.productSearchKeywordRepository = productSearchKeywordRepository;
    }

    @Transactional(readOnly = true)
    public List<String> findKeywords(Long productId) {
        return productSearchKeywordRepository
                .findByProduct_IdOrderByIdAsc(productId)
                .stream()
                .map(ProductSearchKeyword::getKeyword)
                .toList();
    }

    @Transactional
    public void syncKeywords(Product product, List<String> submittedKeywords) {
        List<String> normalizedKeywords = normalizeAndValidate(submittedKeywords);

        List<ProductSearchKeyword> existingKeywords = productSearchKeywordRepository
                .findByProduct_IdOrderByIdAsc(product.getId());

        Map<String, ProductSearchKeyword> existingByComparisonKey = existingKeywords.stream()
                .collect(Collectors.toMap(
                        existing -> normalizeForComparison(existing.getKeyword()),
                        existing -> existing));

        Set<String> submittedComparisonKeys = normalizedKeywords.stream()
                .map(this::normalizeForComparison)
                .collect(Collectors.toSet());

        List<ProductSearchKeyword> deleteTargets = existingKeywords.stream()
                .filter(existing -> !submittedComparisonKeys.contains(
                        normalizeForComparison(existing.getKeyword())))
                .toList();

        if (!deleteTargets.isEmpty()) {
            productSearchKeywordRepository.deleteAll(deleteTargets);
        }

        List<ProductSearchKeyword> insertTargets = new ArrayList<>();

        for (String keyword : normalizedKeywords) {
            String comparisonKey = normalizeForComparison(keyword);
            ProductSearchKeyword existing = existingByComparisonKey.get(comparisonKey);

            if (existing == null) {
                insertTargets.add(new ProductSearchKeyword(product, keyword));
            } else if (!existing.getKeyword().equals(keyword)) {
                existing.setKeyword(keyword);
            }
        }

        if (!insertTargets.isEmpty()) {
            productSearchKeywordRepository.saveAll(insertTargets);
        }
    }

    private List<String> normalizeAndValidate(List<String> submittedKeywords) {
        if (submittedKeywords == null) {
            return List.of();
        }

        List<String> normalizedKeywords = new ArrayList<>();

        for (String submittedKeyword : submittedKeywords) {
            if (submittedKeyword == null) {
                continue;
            }

            String keyword = submittedKeyword.trim();

            if (keyword.isEmpty()) {
                continue;
            }

            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                throw new InvalidProductSearchKeywordException(
                        "検索キーワードは50文字以内で入力してください。");
            }

            normalizedKeywords.add(keyword);
        }

        if (normalizedKeywords.size() > MAX_KEYWORD_COUNT) {
            throw new InvalidProductSearchKeywordException(
                    "検索キーワードは10件以内で入力してください。");
        }

        Set<String> seen = new HashSet<>();

        for (String keyword : normalizedKeywords) {
            String comparisonKey = normalizeForComparison(keyword);

            if (!seen.add(comparisonKey)) {
                throw new InvalidProductSearchKeywordException(
                        "同じ検索キーワードが複数入力されています。");
            }
        }

        return normalizedKeywords;
    }

    private String normalizeForComparison(String keyword) {
        return keyword.toLowerCase(Locale.ROOT);
    }
}
