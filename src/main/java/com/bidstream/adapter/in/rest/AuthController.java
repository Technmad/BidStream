package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.AuthDtos.AuthResponse;
import com.bidstream.adapter.in.rest.dto.AuthDtos.LoginRequest;
import com.bidstream.adapter.in.rest.dto.AuthDtos.RefreshRequest;
import com.bidstream.adapter.in.rest.dto.AuthDtos.RegisterRequest;
import com.bidstream.application.AuthService;
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
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.username(), request.email(), request.password());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        var tokens = authService.login(request.username(), request.password());
        return ResponseEntity.ok(AuthResponse.bearer(tokens.accessToken(), tokens.refreshToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        var tokens = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(AuthResponse.bearer(tokens.accessToken(), tokens.refreshToken()));
    }
}
