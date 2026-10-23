package com.nexora.transaction_service.dto;

import com.nexora.transaction_service.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class TransactionRequest {



    @NotNull(message = "Account id is required")
    @Positive(message = "Account id must be greater than 0")
    private Long accountId;

    private Long receiverAccountId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Transaction type is required")
    private TransactionType type;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    public TransactionRequest() {
    }

    public TransactionRequest(
            Long accountId,
            Long receiverAccountId,
            BigDecimal amount,
            TransactionType type,
            String idempotencyKey
    ){
        this.accountId=accountId;
        this.receiverAccountId=receiverAccountId;
        this.amount=amount;
        this.type=type;
        this.idempotencyKey=idempotencyKey;

    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getReceiverAccountId() {
        return receiverAccountId;
    }

    public void setReceiverAccountId(Long receiverAccountId) {
        this.receiverAccountId = receiverAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
