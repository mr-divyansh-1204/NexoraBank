package com.nexora.accountservice.entity;

import jakarta.persistence.*;

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

    @Column(nullable = false)
    private double balance;

    public Account(){

    }
    public Account(Long id, String accountNumber, String accountHolderName, Double balance){
        this.id=id;
        this.accountNumber=accountNumber;
        this.accountHolderName=accountHolderName;
        this.balance=balance;
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
    public double getBalance(){
        return balance;
    }
    public void setBalance(double balance){
        this.balance=balance;
    }

}
