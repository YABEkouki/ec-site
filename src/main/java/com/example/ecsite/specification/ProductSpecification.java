package com.example.ecsite.specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductSearchKeyword;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

public final class ProductSpecification {

    private ProductSpecification() {
    }

    public static Specification<Product> isActive() {

        return (root, query, criteriaBuilder) -> criteriaBuilder.isTrue(root.get("active"));
    }

    public static Specification<Product> hasCategory(Long categoryId) {

        return (root, query, criteriaBuilder) -> {

            if (categoryId == null) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.equal(
                    root.get("category").get("id"),
                    categoryId);
        };
    }

    public static Specification<Product> priceGreaterThanOrEqualTo(Integer minPrice) {

        return (root, query, criteriaBuilder) -> {

            if (minPrice == null) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.greaterThanOrEqualTo(
                    root.get("price"),
                    minPrice);
        };
    }

    public static Specification<Product> priceLessThanOrEqualTo(Integer maxPrice) {

        return (root, query, criteriaBuilder) -> {

            if (maxPrice == null) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.lessThanOrEqualTo(
                    root.get("price"),
                    maxPrice);
        };
    }

    public static Specification<Product> inStockOnly(boolean inStockOnly) {

        return (root, query, criteriaBuilder) -> {

            if (!inStockOnly) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.greaterThan(
                    root.get("stock"),
                    0);
        };
    }

    public static Specification<Product> containsKeywords(
            String keyword) {

        List<String> terms = splitSearchTerms(keyword);

        return (root, query, criteriaBuilder) -> {

            if (terms.isEmpty()) {
                return criteriaBuilder.conjunction();
            }

            List<Predicate> termPredicates = new ArrayList<>();

            for (String term : terms) {

                String pattern = "%" + term.toLowerCase(Locale.ROOT) + "%";

                Predicate namePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        pattern);

                Predicate descriptionPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("description")),
                        pattern);

                Subquery<Integer> keywordSubquery = query.subquery(Integer.class);

                Root<ProductSearchKeyword> keywordRoot = keywordSubquery.from(ProductSearchKeyword.class);

                keywordSubquery.select(
                        criteriaBuilder.literal(1));

                keywordSubquery.where(
                        criteriaBuilder.equal(
                                keywordRoot.get("product"),
                                root),
                        criteriaBuilder.like(
                                criteriaBuilder.lower(
                                        keywordRoot.get("keyword")),
                                pattern));

                Predicate registeredKeywordPredicate = criteriaBuilder.exists(keywordSubquery);

                termPredicates.add(
                        criteriaBuilder.or(
                                namePredicate,
                                descriptionPredicate,
                                registeredKeywordPredicate));
            }

            return criteriaBuilder.and(
                    termPredicates.toArray(new Predicate[0]));
        };
    }

    private static List<String> splitSearchTerms(
            String keyword) {

        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        return List.of(
                keyword.trim().split("[\\s\\u3000]+"));
    }

}
