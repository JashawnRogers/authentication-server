package com.jashawn.authentication_server.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.function.Function;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private static String SECRET_KEY;

//    Claims.getSubject() returns the username/email of the user
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

//    Returns a specific claim from list of claims
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {

//       Signing Key = Key used to digitally sign JWT
//        It is used to verify if the sender of the JWT is who they claim to be
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

//    Decode secret key to create signing key
    private @NonNull SecretKey getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
