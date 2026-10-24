package com.tesa.foodbridge.shared;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(message);
    }
}