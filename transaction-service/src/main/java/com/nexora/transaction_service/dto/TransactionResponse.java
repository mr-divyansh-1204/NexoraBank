package com.nexora.transaction_service.dto;

import com.nexora.transaction_service.entity.TransactionStatus;
import com.nexora.transaction_service.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionResponse {

    private Long id;
    private String transactionReference;
    private Long accountId;
    private Long receiverAccountId;
    private BigDecimal amount;
    private TransactionType type;
    private TransactionStatus status;
    private LocalDateTime transactionDate;
    private String failureReason;

    public TransactionResponse(){

    }
    public TransactionResponse(
             Long id,
             String transactionReference,
             Long accountId,
             Long receiverAccountId,
             BigDecimal amount,
             TransactionType type,
             TransactionStatus status,
             LocalDateTime transactionDate,
             String failureReason
    ){
        this.id=id;
        this.transactionReference=transactionReference;
        this.accountId=accountId;
        this.receiverAccountId=receiverAccountId;
        this.amount=amount;
        this.type=type;
        this.status=status;
        this.transactionDate=transactionDate;
        this.failureReason=failureReason;

    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public void setTransactionReference(String transactionReference) {
        this.transactionReference = transactionReference;
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

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public LocalDateTime getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDateTime transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
