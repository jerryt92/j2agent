package io.github.jerryt92.j2agent.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class JwtServiceImpl implements JwtService, InitializingBean {
    private Algorithm algorithm;
    private final JwtProperties jwtProperties;

    public JwtServiceImpl(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        AlgorithmBuilder.AlgType algName = AlgorithmBuilder.AlgType.valueOf(jwtProperties.getAlgorithm());
        if (AlgorithmBuilder.AlgType.EDDSA.equals(algName) || AlgorithmBuilder.AlgType.ED25519.equals(algName)) {
            AlgorithmBuilder.registerBouncyCastleProvider();
        }
        algorithm = AlgorithmBuilder.newBuilder()
                .algorithm(algName)
                .secret(jwtProperties.getSecret())
                .prvKeyFile(jwtProperties.getPrvKeyFile())
                .pubKeyFile(jwtProperties.getPubKeyFile())
                .kmacOutputLengthBytes(jwtProperties.getKmacOutputLengthBytes())
                .build();
    }

    @Override
    public String signToken(com.auth0.jwt.JWTCreator.Builder jwtBuilder) {
        return jwtBuilder.sign(algorithm);
    }

    @Override
    public DecodedJWT verifyToken(String token) {
        return JWT.require(algorithm).build().verify(token);
    }

    @Override
    public long expiresTime(TerminalType terminalType, boolean rememberMe) {
        if (rememberMe) {
            return jwtProperties.getExpiresForRememberMe();
        }
        if (terminalType == null) {
            return jwtProperties.getExpiresForBrowser();
        }
        return switch (terminalType) {
            case OPENAPI -> jwtProperties.getExpiresForOpenapi();
            case MOBILE -> jwtProperties.getExpiresForMobile();
            case BROWSER -> jwtProperties.getExpiresForBrowser();
        };
    }
}
