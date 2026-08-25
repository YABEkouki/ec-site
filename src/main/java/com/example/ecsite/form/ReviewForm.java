package com.example.ecsite.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ReviewForm {

    @NotNull(message = "評価を選択してください")
    @Min(value = 1, message = "評価は1以上を指定してください")
    @Max(value = 5, message = "評価は5以下を指定してください")
    private Integer rating;

    @NotBlank(message = "コメントを入力してください")
    @Size(max = 1000, message = "コメントは1000文字以内で入力してください")
    private String comment;

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}