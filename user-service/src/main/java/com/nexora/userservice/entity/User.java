package com.nexora.userservice.entity;

import jakarta.persistence.*;

@Entity
@Table(name="users")
public class User {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

@Column(nullable = false)
private String name;

@Column(nullable = false, unique = true)
private String email;

@Column(nullable = false)
private String mobile;

    public User(){

    }
    public User(Long id,String name,String email, String mobile) {
        this.id=id;
        this.name=name;
        this.email=email;
        this.mobile=mobile;
    }
    public Long getId(){
        return id;
    }
    public void setId(Long id){
        this.id=id;
    }
    public String getName(){
        return name;
    }
    public void setName(String name){
        this.name=name;
    }
    public String getEmail(){
        return email;
    }
    public void setEmail(String email){
        this.email=email;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }
}
