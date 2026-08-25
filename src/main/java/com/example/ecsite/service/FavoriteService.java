package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Favorite;
import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.FavoriteAlreadyExistsException;
import com.example.ecsite.repository.FavoriteRepository;

@Service
@Transactional
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ProductService productService;
    private final UserService userService;

    public FavoriteService(
            FavoriteRepository favoriteRepository,
            ProductService productService,
            UserService userService) {

        this.favoriteRepository = favoriteRepository;
        this.productService = productService;
        this.userService = userService;
    }

    public Favorite addFavorite(
            Long productId,
            Long userId) {

        if (favoriteRepository.existsByUserIdAndProductId(
                userId,
                productId)) {

            throw new FavoriteAlreadyExistsException();
        }

        Product product = productService.findById(productId);
        User user = userService.findById(userId);

        Favorite favorite = new Favorite();
        favorite.setProduct(product);
        favorite.setUser(user);

        return favoriteRepository.save(favorite);
    }

    public void removeFavorite(
            Long productId,
            Long userId) {

        favoriteRepository
                .findByUserIdAndProductId(userId, productId)
                .ifPresent(favoriteRepository::delete);
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(
            Long productId,
            Long userId) {

        return favoriteRepository
                .existsByUserIdAndProductId(
                        userId,
                        productId);
    }

    @Transactional(readOnly = true)
    public List<Favorite> findFavoritesByUserId(Long userId) {

        return favoriteRepository
                .findByUserIdAndProductActiveTrueOrderByCreatedAtDesc(
                        userId);
    }
}