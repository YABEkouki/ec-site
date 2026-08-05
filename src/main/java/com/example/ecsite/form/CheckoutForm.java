package com.example.ecsite.form;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CheckoutForm implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "受取人氏名を入力してください。")
    @Size(max = 100,
            message = "受取人氏名は100文字以内で入力してください。")
    private String shippingName;

    @NotBlank(message = "郵便番号を入力してください。")
    @Pattern(
            regexp = "^\\d{3}-?\\d{4}$",
            message = "郵便番号は123-4567の形式で入力してください。")
    private String shippingPostalCode;

    @NotBlank(message = "都道府県を入力してください。")
    @Size(max = 20,
            message = "都道府県は20文字以内で入力してください。")
    private String shippingPrefecture;

    @NotBlank(message = "市区町村を入力してください。")
    @Size(max = 100,
            message = "市区町村は100文字以内で入力してください。")
    private String shippingCity;

    @NotBlank(message = "番地・建物名を入力してください。")
    @Size(max = 200,
            message = "番地・建物名は200文字以内で入力してください。")
    private String shippingAddressLine;

    @NotBlank(message = "電話番号を入力してください。")
    @Pattern(
            regexp = "^0\\d{1,4}-?\\d{1,4}-?\\d{3,4}$",
            message = "電話番号の形式が正しくありません。")
    private String shippingPhone;

    public String getShippingName() {
        return shippingName;
    }

    public void setShippingName(String shippingName) {
        this.shippingName = shippingName;
    }

    public String getShippingPostalCode() {
        return shippingPostalCode;
    }

    public void setShippingPostalCode(
            String shippingPostalCode) {

        this.shippingPostalCode = shippingPostalCode;
    }

    public String getShippingPrefecture() {
        return shippingPrefecture;
    }

    public void setShippingPrefecture(
            String shippingPrefecture) {

        this.shippingPrefecture = shippingPrefecture;
    }

    public String getShippingCity() {
        return shippingCity;
    }

    public void setShippingCity(String shippingCity) {
        this.shippingCity = shippingCity;
    }

    public String getShippingAddressLine() {
        return shippingAddressLine;
    }

    public void setShippingAddressLine(
            String shippingAddressLine) {

        this.shippingAddressLine = shippingAddressLine;
    }

    public String getShippingPhone() {
        return shippingPhone;
    }

    public void setShippingPhone(String shippingPhone) {
        this.shippingPhone = shippingPhone;
    }
}