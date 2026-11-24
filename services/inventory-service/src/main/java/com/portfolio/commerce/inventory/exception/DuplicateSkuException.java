package com.portfolio.commerce.inventory.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        this(sku, null);
    }

    public DuplicateSkuException(String sku, Throwable cause) {
        super("A product with SKU " + sku + " already exists", cause);
    }
}
