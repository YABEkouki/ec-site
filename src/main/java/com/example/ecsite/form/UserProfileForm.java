package com.example.ecsite.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UserProfileForm {

    @NotBlank(message = "氏名を入力してください。")
    @Size(max = 100, message = "氏名は100文字以内で入力してください。")
    private String name;

    @NotBlank(message = "郵便番号を入力してください。")
    @Pattern(
            regexp = "^\\d{3}-?\\d{4}$",
            message = "郵便番号は123-4567の形式で入力してください。")
    private String postalCode;

    @NotBlank(message = "都道府県を入力してください。")
    @Size(max = 20, message = "都道府県は20文字以内で入力してください。")
    private String prefecture;

    @NotBlank(message = "市区町村を入力してください。")
    @Size(max = 100, message = "市区町村は100文字以内で入力してください。")
    private String city;

    @NotBlank(message = "番地・建物名を入力してください。")
    @Size(max = 200, message = "番地・建物名は200文字以内で入力してください。")
    private String addressLine;

    @NotBlank(message = "電話番号を入力してください。")
    @Size(max = 20, message = "電話番号は20文字以内で入力してください。")
    @Pattern(
            regexp = "^0\\d{1,4}-?\\d{1,4}-?\\d{3,4}$",
            message = "電話番号の形式が正しくありません。")
    private String phone;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getPrefecture() {
        return prefecture;
    }

    public void setPrefecture(String prefecture) {
        this.prefecture = prefecture;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}