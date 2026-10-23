package com.nexora.transaction_service.exception;

public class ConcurrencyException extends RuntimeException{

    public ConcurrencyException(String message){
        super(message);
    }
}
