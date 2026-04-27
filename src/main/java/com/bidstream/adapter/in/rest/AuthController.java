package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.AuthDtos.AuthResponse;
import com.bidstream.adapter.in.rest.dto.AuthDtos.LoginRequest;
import com.bidstream.adapter.in.rest.dto.AuthDtos.RefreshRequest;
import com.bidstream.adapter.in.rest.dto.AuthDtos.RegisterRequest;
import com.bidstream.application.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Registration and JWT access/refresh token issuance")
@SecurityRequirements
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user")
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.username(), request.email(), request.password());
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Returns a short-lived access token and a refresh token.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        var tokens = authService.login(request.username(), request.password());
        return ResponseEntity.ok(AuthResponse.bearer(tokens.accessToken(), tokens.refreshToken()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access/refresh token pair")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        var tokens = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(AuthResponse.bearer(tokens.accessToken(), tokens.refreshToken()));
    }
}
