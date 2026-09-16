package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.ProductSearchKeyword;
import com.example.ecsite.exception.InvalidProductSearchKeywordException;
import com.example.ecsite.repository.ProductSearchKeywordRepository;

@ExtendWith(MockitoExtension.class)
class ProductSearchKeywordServiceTest {

    @Mock
    private ProductSearchKeywordRepository productSearchKeywordRepository;

    private ProductSearchKeywordService productSearchKeywordService;

    private Product product;

    @BeforeEach
    void setUp() {
        productSearchKeywordService =
                new ProductSearchKeywordService(productSearchKeywordRepository);

        product = new Product();
    }

    @Test
    void syncKeywordsTrimsAndIgnoresBlankKeywords() {
        when(productSearchKeywordRepository
                .findByProduct_IdOrderByIdAsc(product.getId()))
                .thenReturn(List.of());

        productSearchKeywordService.syncKeywords(
                product,
                List.of("  キャンプ  ", "", "   ", " 軽量 "));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductSearchKeyword>> captor =
                ArgumentCaptor.forClass(List.class);

        verify(productSearchKeywordRepository).saveAll(captor.capture());

        List<ProductSearchKeyword> saved = captor.getValue();

        assertEquals(2, saved.size());
        assertEquals("キャンプ", saved.get(0).getKeyword());
        assertEquals("軽量", saved.get(1).getKeyword());
    }

    @Test
    void syncKeywordsRejectsKeywordLongerThan50Characters() {
        String keyword = "a".repeat(51);

        assertThrows(
                InvalidProductSearchKeywordException.class,
                () -> productSearchKeywordService.syncKeywords(
                        product, List.of(keyword)));

        verify(productSearchKeywordRepository, never())
                .findByProduct_IdOrderByIdAsc(product.getId());
    }

    @Test
    void syncKeywordsRejectsMoreThan10Keywords() {
        List<String> keywords = List.of(
                "1", "2", "3", "4", "5",
                "6", "7", "8", "9", "10", "11");

        assertThrows(
                InvalidProductSearchKeywordException.class,
                () -> productSearchKeywordService.syncKeywords(
                        product, keywords));

        verify(productSearchKeywordRepository, never())
                .findByProduct_IdOrderByIdAsc(product.getId());
    }

    @Test
    void syncKeywordsRejectsCaseInsensitiveDuplicates() {
        assertThrows(
                InvalidProductSearchKeywordException.class,
                () -> productSearchKeywordService.syncKeywords(
                        product, List.of("Camp", "camp")));

        verify(productSearchKeywordRepository, never())
                .findByProduct_IdOrderByIdAsc(product.getId());
    }

    @Test
    void syncKeywordsAddsAndDeletesOnlyChangedKeywords() {
        ProductSearchKeyword camp =
                new ProductSearchKeyword(product, "キャンプ");
        ProductSearchKeyword lightweight =
                new ProductSearchKeyword(product, "軽量");

        when(productSearchKeywordRepository
                .findByProduct_IdOrderByIdAsc(product.getId()))
                .thenReturn(List.of(camp, lightweight));

        productSearchKeywordService.syncKeywords(
                product,
                List.of("キャンプ", "防水"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductSearchKeyword>> deleteCaptor =
                ArgumentCaptor.forClass(List.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductSearchKeyword>> saveCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(productSearchKeywordRepository)
                .deleteAll(deleteCaptor.capture());
        verify(productSearchKeywordRepository)
                .saveAll(saveCaptor.capture());

        assertEquals(1, deleteCaptor.getValue().size());
        assertEquals("軽量",
                deleteCaptor.getValue().get(0).getKeyword());

        assertEquals(1, saveCaptor.getValue().size());
        assertEquals("防水",
                saveCaptor.getValue().get(0).getKeyword());
    }

    @Test
    void syncKeywordsUpdatesKeywordCasingWithoutDeleteOrInsert() {
        ProductSearchKeyword existing =
                new ProductSearchKeyword(product, "Camp");

        when(productSearchKeywordRepository
                .findByProduct_IdOrderByIdAsc(product.getId()))
                .thenReturn(List.of(existing));

        productSearchKeywordService.syncKeywords(
                product,
                List.of("camp"));

        assertEquals("camp", existing.getKeyword());

        verify(productSearchKeywordRepository, never())
                .deleteAll(anyList());
        verify(productSearchKeywordRepository, never())
                .saveAll(anyList());
    }

    @Test
    void syncKeywordsDoesNothingWhenKeywordsAreUnchanged() {
        ProductSearchKeyword camp =
                new ProductSearchKeyword(product, "キャンプ");
        ProductSearchKeyword lightweight =
                new ProductSearchKeyword(product, "軽量");

        when(productSearchKeywordRepository
                .findByProduct_IdOrderByIdAsc(product.getId()))
                .thenReturn(List.of(camp, lightweight));

        productSearchKeywordService.syncKeywords(
                product,
                List.of("キャンプ", "軽量"));

        verify(productSearchKeywordRepository, never())
                .deleteAll(anyList());
        verify(productSearchKeywordRepository, never())
                .saveAll(anyList());
    }
}
