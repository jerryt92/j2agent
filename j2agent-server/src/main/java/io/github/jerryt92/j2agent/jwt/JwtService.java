package io.github.jerryt92.j2agent.jwt;

import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.interfaces.DecodedJWT;

public interface JwtService {
    String signToken(JWTCreator.Builder jwtBuilder);

    DecodedJWT verifyToken(String token);

    long expiresTime(TerminalType terminalType, boolean rememberMe);
}
