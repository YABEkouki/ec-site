package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductSearchKeyword;
import com.example.ecsite.specification.ProductSpecification;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void searchMatchesProductName() {

        Category category = createCategory("商品名検索カテゴリ");

        Product target = createProduct(
                "軽量キャンプチェア",
                5000,
                10,
                "折りたたみ式です。",
                true,
                category);

        createProduct(
                "アウトドアテーブル",
                8000,
                10,
                "折りたたみ式です。",
                true,
                category);

        Page<Product> result = search("キャンプ");

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchMatchesProductDescription() {

        Category category = createCategory("説明検索カテゴリ");

        Product target = createProduct(
                "アウトドアチェア",
                5000,
                10,
                "キャンプで使える軽量チェアです。",
                true,
                category);

        createProduct(
                "アウトドアテーブル",
                8000,
                10,
                "折りたたみ式です。",
                true,
                category);

        Page<Product> result = search("キャンプ");

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchMatchesRegisteredSearchKeyword() {

        Category category = createCategory("登録キーワード検索カテゴリ");

        Product target = createProduct(
                "折りたたみチェア",
                5000,
                10,
                "持ち運びに便利です。",
                true,
                category);

        createSearchKeyword(target, "キャンプ");

        createProduct(
                "アウトドアテーブル",
                8000,
                10,
                "折りたたみ式です。",
                true,
                category);

        flushAndClear();

        Page<Product> result = search("キャンプ");

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchCombinesMultipleTermsWithAnd() {

        Category category = createCategory("複数語検索カテゴリ");

        Product target = createProduct(
                "キャンプチェア",
                5000,
                10,
                "持ち運びに便利です。",
                true,
                category);

        createSearchKeyword(target, "軽量");

        createProduct(
                "キャンプテーブル",
                8000,
                10,
                "大型の商品です。",
                true,
                category);

        flushAndClear();

        Page<Product> result = search("キャンプ 軽量");

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchMatchesMultipleTermsInDifferentSearchKeywordRows() {

        Category category = createCategory("複数キーワード行カテゴリ");

        Product target = createProduct(
                "折りたたみチェア",
                5000,
                10,
                "持ち運びに便利です。",
                true,
                category);

        createSearchKeyword(target, "キャンプ");
        createSearchKeyword(target, "軽量");

        flushAndClear();

        Page<Product> result = search("キャンプ 軽量");

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchSplitsTermsByFullWidthSpace() {

        Category category = createCategory("全角スペース検索カテゴリ");

        Product target = createProduct(
                "キャンプチェア",
                5000,
                10,
                "軽量モデルです。",
                true,
                category);

        Page<Product> result = search("キャンプ　軽量");

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchExcludesInactiveProducts() {

        Category category = createCategory("非公開商品検索カテゴリ");

        createProduct(
                "キャンプチェア",
                5000,
                10,
                "軽量モデルです。",
                false,
                category);

        Page<Product> result = search("キャンプ");

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void searchFiltersByCategory() {

        Category targetCategory = createCategory("カテゴリ絞り込み対象");

        Category otherCategory = createCategory("カテゴリ絞り込み対象外");

        Product target = createProduct(
                "対象商品",
                5000,
                10,
                "商品説明",
                true,
                targetCategory);

        createProduct(
                "別カテゴリ商品",
                5000,
                10,
                "商品説明",
                true,
                otherCategory);

        Specification<Product> specification = Specification.where(
                ProductSpecification.isActive())
                .and(ProductSpecification.hasCategory(
                        targetCategory.getId()));

        Page<Product> result = productRepository.findAll(
                specification,
                PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchFiltersByMinimumAndMaximumPrice() {

        Category category = createCategory("価格絞り込みカテゴリ");

        createProduct(
                "低価格商品",
                999,
                10,
                "商品説明",
                true,
                category);

        Product minimumPriceProduct = createProduct(
                "最低価格境界商品",
                1000,
                10,
                "商品説明",
                true,
                category);

        Product maximumPriceProduct = createProduct(
                "最高価格境界商品",
                5000,
                10,
                "商品説明",
                true,
                category);

        createProduct(
                "高価格商品",
                5001,
                10,
                "商品説明",
                true,
                category);

        Specification<Product> specification = Specification.where(
                ProductSpecification.isActive())
                .and(
                        ProductSpecification
                                .priceGreaterThanOrEqualTo(
                                        1000))
                .and(
                        ProductSpecification
                                .priceLessThanOrEqualTo(
                                        5000));

        Page<Product> result = productRepository.findAll(
                specification,
                PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactlyInAnyOrder(
                        minimumPriceProduct.getId(),
                        maximumPriceProduct.getId());
    }

    @Test
    void searchFiltersByInStockOnly() {

        Category category = createCategory("在庫絞り込みカテゴリ");

        Product target = createProduct(
                "在庫あり商品",
                5000,
                1,
                "商品説明",
                true,
                category);

        createProduct(
                "在庫なし商品",
                5000,
                0,
                "商品説明",
                true,
                category);

        Specification<Product> specification = Specification.where(
                ProductSpecification.isActive())
                .and(
                        ProductSpecification.inStockOnly(
                                true));

        Page<Product> result = productRepository.findAll(
                specification,
                PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void searchCombinesAllConditionsWithAnd() {

        Category targetCategory = createCategory("複合検索対象カテゴリ");

        Category otherCategory = createCategory("複合検索対象外カテゴリ");

        Product target = createProduct(
                "キャンプチェア",
                3000,
                5,
                "持ち運びに便利です。",
                true,
                targetCategory);

        createSearchKeyword(target, "軽量");

        Product wrongCategory = createProduct(
                "キャンプチェア",
                3000,
                5,
                "持ち運びに便利です。",
                true,
                otherCategory);

        createSearchKeyword(wrongCategory, "軽量");

        Product outOfPriceRange = createProduct(
                "キャンプチェア",
                8000,
                5,
                "持ち運びに便利です。",
                true,
                targetCategory);

        createSearchKeyword(outOfPriceRange, "軽量");

        Product outOfStock = createProduct(
                "キャンプチェア",
                3000,
                0,
                "持ち運びに便利です。",
                true,
                targetCategory);

        createSearchKeyword(outOfStock, "軽量");

        flushAndClear();

        Specification<Product> specification = Specification.where(
                ProductSpecification.isActive())
                .and(
                        ProductSpecification.containsKeywords(
                                "キャンプ 軽量"))
                .and(
                        ProductSpecification.hasCategory(
                                targetCategory.getId()))
                .and(
                        ProductSpecification
                                .priceGreaterThanOrEqualTo(
                                        1000))
                .and(
                        ProductSpecification
                                .priceLessThanOrEqualTo(
                                        5000))
                .and(
                        ProductSpecification.inStockOnly(
                                true));

        Page<Product> result = productRepository.findAll(
                specification,
                PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void specificationSearchFetchesCategory() {

        Category category = createCategory("アウトドア");

        createProduct(
                "キャンプ用品",
                3000,
                5,
                "キャンプ用の商品",
                true,
                category);

        entityManager.flush();
        entityManager.clear();

        Specification<Product> specification = ProductSpecification.isActive();

        Page<Product> result = productRepository.findAll(
                specification,
                PageRequest.of(0, 10));

        Product foundProduct = result.getContent().get(0);

        entityManager.clear();

        assertEquals(
                "アウトドア",
                foundProduct.getCategory().getName());
    }

    private Page<Product> search(String keyword) {

        Specification<Product> specification = Specification.where(
                ProductSpecification.isActive())
                .and(ProductSpecification.containsKeywords(
                        keyword));

        return productRepository.findAll(
                specification,
                PageRequest.of(0, 20));
    }

    private Category createCategory(String name) {

        Category category = new Category(name);
        category.setActive(true);

        return categoryRepository.save(category);
    }

    private Product createProduct(
            String name,
            int price,
            int stock,
            String description,
            boolean active,
            Category category) {

        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        product.setStock(stock);
        product.setDescription(description);
        product.setActive(active);
        product.setCategory(category);

        return productRepository.save(product);
    }

    private void createSearchKeyword(
            Product product,
            String keyword) {

        entityManager.persist(
                new ProductSearchKeyword(product, keyword));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
