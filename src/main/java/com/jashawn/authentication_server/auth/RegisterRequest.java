package com.jashawn.authentication_server.auth;

public record RegisterRequest(String firstName, String lastName, String email, String password) {
}
