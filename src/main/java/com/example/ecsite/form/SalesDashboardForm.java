package com.example.ecsite.form;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

public class SalesDashboardForm {

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;

        public LocalDate getFrom() {
                return from;
        }

        public void setFrom(LocalDate from) {
                this.from = from;
        }

        public LocalDate getTo() {
                return to;
        }

        public void setTo(LocalDate to) {
                this.to = to;
        }
}