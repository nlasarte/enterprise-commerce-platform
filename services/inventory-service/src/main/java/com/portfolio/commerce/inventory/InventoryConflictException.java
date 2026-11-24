package com.portfolio.commerce.inventory;

class InventoryConflictException extends RuntimeException {

    InventoryConflictException(String message) {
        super(message);
    }

    InventoryConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
