package com.nexora.accountservice.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name="accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountNumber;

    @Column(nullable = false)
    private String accountHolderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false)
    private Long userId;

    @Version
    @Column(nullable = false)
    private Long version;

    public Account(){

    }
    public Account(Long id, String accountNumber, String accountHolderName, BigDecimal balance, Long userId){
        this.id=id;
        this.accountNumber=accountNumber;
        this.accountHolderName=accountHolderName;
        this.balance=balance;
        this.userId=userId;
    }

    public Long getId(){
        return id;
    }

    public void setId(Long id){
        this.id=id;
    }

    public String getAccountNumber(){
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber){
        this.accountNumber=accountNumber;
    }

    public String getAccountHolderName(){
        return accountHolderName;
    }
    public void setAccountHolderName(String accountHolderName){
        this.accountHolderName=accountHolderName;
    }
    public BigDecimal getBalance(){
        return balance;
    }
    public void setBalance(BigDecimal balance){
        this.balance=balance;
    }
    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
