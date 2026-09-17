package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import com.example.ecsite.entity.Category;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderItem;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductSearchKeyword;
import com.example.ecsite.entity.User;
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

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

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

    @Test
    void findAvailableProductsExcludesOutOfStockAndInactiveProducts() {

        Category category = createCategory("新着商品カテゴリ");

        Product availableProduct = createProduct(
                "公開在庫あり商品",
                1000,
                5,
                "商品説明",
                true,
                category);

        createProduct(
                "公開在庫なし商品",
                2000,
                0,
                "商品説明",
                true,
                category);

        createProduct(
                "非公開在庫あり商品",
                3000,
                5,
                "商品説明",
                false,
                category);

        Page<Product> result = productRepository
                .findByActiveTrueAndStockGreaterThan(
                        0,
                        PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(Product::getId)
                .containsExactly(availableProduct.getId());
    }

    @Test
    void findPopularProductsRanksByQuantityOrderCountAndProductId() {

        User user = createUser("popular-ranking-user");

        Category category = createCategory("人気商品ランキングカテゴリ");

        Product quantityFirst = createProduct(
                "販売数量1位",
                1000,
                10,
                "商品説明",
                true,
                category);

        Product orderCountFirst = createProduct(
                "注文件数優先",
                2000,
                10,
                "商品説明",
                true,
                category);

        Product orderCountSecond = createProduct(
                "注文件数劣後",
                3000,
                10,
                "商品説明",
                true,
                category);

        Product idTieFirst = createProduct(
                "ID同率先",
                4000,
                10,
                "商品説明",
                true,
                category);

        Product idTieSecond = createProduct(
                "ID同率後",
                5000,
                10,
                "商品説明",
                true,
                category);

        // 販売数量 6
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 10, 10, 0),
                quantityFirst,
                6,
                OrderStatus.PAID);

        // 販売数量 5、注文件数 2
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 11, 10, 0),
                orderCountFirst,
                2,
                OrderStatus.PAID);

        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 12, 10, 0),
                orderCountFirst,
                3,
                OrderStatus.SHIPPED);

        // 販売数量 5、注文件数 1
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 13, 10, 0),
                orderCountSecond,
                5,
                OrderStatus.PAID);

        // 以下2商品は販売数量・注文件数とも同じ
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 14, 10, 0),
                idTieFirst,
                4,
                OrderStatus.PAID);

        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 14, 11, 0),
                idTieSecond,
                4,
                OrderStatus.PAID);

        entityManager.flush();
        entityManager.clear();

        List<Product> result = productRepository.findPopularProducts(
                LocalDateTime.of(2026, 8, 19, 0, 0),
                LocalDateTime.of(2026, 9, 18, 0, 0),
                PageRequest.of(0, 10));

        assertThat(result)
                .extracting(Product::getId)
                .containsExactly(
                        quantityFirst.getId(),
                        orderCountFirst.getId(),
                        orderCountSecond.getId(),
                        idTieSecond.getId(),
                        idTieFirst.getId());
    }

    @Test
    void findPopularProductsUsesOnlyEligibleOrdersAndProducts() {

        User user = createUser("popular-eligible-user");

        Category category = createCategory("人気商品対象条件カテゴリ");

        Product target = createProduct(
                "対象商品",
                1000,
                10,
                "商品説明",
                true,
                category);

        Product inactive = createProduct(
                "非公開商品",
                2000,
                10,
                "商品説明",
                false,
                category);

        Product outOfStock = createProduct(
                "在庫なし商品",
                3000,
                0,
                "商品説明",
                true,
                category);

        // from 境界は対象
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 19, 0, 0),
                target,
                1,
                OrderStatus.PAID);

        // SHIPPEDも対象
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 17, 23, 59),
                target,
                1,
                OrderStatus.SHIPPED);

        // 期間外
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 8, 18, 23, 59),
                target,
                100,
                OrderStatus.PAID);

        // toExclusive境界は対象外
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 18, 0, 0),
                target,
                100,
                OrderStatus.PAID);

        // 未払い
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 10, 10, 0),
                target,
                100,
                OrderStatus.ORDERED);

        // キャンセル
        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 10, 11, 0),
                target,
                100,
                OrderStatus.CANCELLED);

        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 10, 12, 0),
                inactive,
                100,
                OrderStatus.PAID);

        createOrderWithItem(
                user.getId(),
                LocalDateTime.of(2026, 9, 10, 13, 0),
                outOfStock,
                100,
                OrderStatus.PAID);

        entityManager.flush();
        entityManager.clear();

        List<Product> result = productRepository.findPopularProducts(
                LocalDateTime.of(2026, 8, 19, 0, 0),
                LocalDateTime.of(2026, 9, 18, 0, 0),
                PageRequest.of(0, 10));

        assertThat(result)
                .extracting(Product::getId)
                .containsExactly(target.getId());
    }

    @Test
    void findPopularProductsLimitsResultsByPageable() {

        User user = createUser("popular-limit-user");

        Category category = createCategory("人気商品件数制限カテゴリ");

        for (int i = 1; i <= 6; i++) {

            Product product = createProduct(
                    "人気商品" + i,
                    1000 * i,
                    10,
                    "商品説明",
                    true,
                    category);

            createOrderWithItem(
                    user.getId(),
                    LocalDateTime.of(2026, 9, 10, 10, i),
                    product,
                    i,
                    OrderStatus.PAID);
        }

        entityManager.flush();
        entityManager.clear();

        List<Product> result = productRepository.findPopularProducts(
                LocalDateTime.of(2026, 8, 19, 0, 0),
                LocalDateTime.of(2026, 9, 18, 0, 0),
                PageRequest.of(0, 5));

        assertThat(result).hasSize(5);
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

    private Order createOrderWithItem(
            Long userId,
            LocalDateTime orderedAt,
            Product product,
            int quantity,
            OrderStatus status) {

        OrderItem item = new OrderItem(
                product.getId(),
                product.getName(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getPrice(),
                quantity);

        Order order = new Order(
                userId,
                item.getSubtotal());

        order.setOrderedAt(orderedAt);
        order.addItem(item);

        if (status == OrderStatus.PAID) {
            order.markAsPaid();
        } else if (status == OrderStatus.SHIPPED) {
            order.markAsPaid();
            order.markAsShipped();
        } else if (status == OrderStatus.CANCELLED) {
            order.cancel();
        }

        Order saved = orderRepository.save(order);
        entityManager.flush();

        return saved;
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(true);

        LocalDateTime now = LocalDateTime.now();

        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User saved = userRepository.save(user);
        entityManager.flush();

        return saved;
    }

}
