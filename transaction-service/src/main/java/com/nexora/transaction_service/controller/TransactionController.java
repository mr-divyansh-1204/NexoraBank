package com.nexora.transaction_service.controller;

import com.nexora.transaction_service.dto.TransactionRequest;
import com.nexora.transaction_service.dto.TransactionResponse;
import com.nexora.transaction_service.dto.TransferRequest;
import com.nexora.transaction_service.security.RequestActor;
import com.nexora.transaction_service.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public TransactionResponse createTransaction(
            @Valid @RequestBody TransactionRequest request,
            HttpServletRequest httpRequest) {
        return transactionService.createTransaction(
                request,
                RequestActor.userId(httpRequest),
                RequestActor.admin(httpRequest));
    }

    @PostMapping("/transfer")
    public TransactionResponse transfer(
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest httpRequest) {
        return transactionService.transfer(
                request,
                RequestActor.userId(httpRequest),
                RequestActor.admin(httpRequest));
    }

    @GetMapping
    public List<TransactionResponse> getAllTransactions(HttpServletRequest httpRequest) {
        return transactionService.getAllTransactions(
                RequestActor.userId(httpRequest),
                RequestActor.admin(httpRequest));
    }

    @GetMapping("/history/{accountId}")
    public List<TransactionResponse> getTransactionsByAccountId(
            @PathVariable("accountId") Long accountId,
            HttpServletRequest httpRequest) {
        return transactionService.getTransactionsByAccountId(
                accountId,
                RequestActor.userId(httpRequest),
                RequestActor.admin(httpRequest));
    }
}
