package com.example.ecsite.exception;

public class ProtectedCategoryException
        extends RuntimeException {

    public ProtectedCategoryException(Long id) {

        super(
                "保護されたカテゴリは変更できません。id="
                        + id);
    }
}