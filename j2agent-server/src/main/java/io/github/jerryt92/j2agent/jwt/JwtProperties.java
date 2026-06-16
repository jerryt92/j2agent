package io.github.jerryt92.j2agent.jwt;

public class JwtProperties {
    private String algorithm;
    private String secret;
    private String prvKeyFile;
    private String pubKeyFile;
    private Long expiresForOpenapi = 7200L;
    private Long expiresForMobile = 2592000L;
    private Long expiresForBrowser = 1800L;
    private Long expiresForRememberMe = 2592000L;
    private Integer kmacOutputLengthBytes;

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getPrvKeyFile() {
        return prvKeyFile;
    }

    public void setPrvKeyFile(String prvKeyFile) {
        this.prvKeyFile = prvKeyFile;
    }

    public String getPubKeyFile() {
        return pubKeyFile;
    }

    public void setPubKeyFile(String pubKeyFile) {
        this.pubKeyFile = pubKeyFile;
    }

    public Long getExpiresForOpenapi() {
        return expiresForOpenapi;
    }

    public void setExpiresForOpenapi(Long expiresForOpenapi) {
        this.expiresForOpenapi = expiresForOpenapi;
    }

    public Long getExpiresForMobile() {
        return expiresForMobile;
    }

    public void setExpiresForMobile(Long expiresForMobile) {
        this.expiresForMobile = expiresForMobile;
    }

    public Long getExpiresForBrowser() {
        return expiresForBrowser;
    }

    public void setExpiresForBrowser(Long expiresForBrowser) {
        this.expiresForBrowser = expiresForBrowser;
    }

    public Long getExpiresForRememberMe() {
        return expiresForRememberMe;
    }

    public void setExpiresForRememberMe(Long expiresForRememberMe) {
        this.expiresForRememberMe = expiresForRememberMe;
    }

    public Integer getKmacOutputLengthBytes() {
        return kmacOutputLengthBytes;
    }

    public void setKmacOutputLengthBytes(Integer kmacOutputLengthBytes) {
        this.kmacOutputLengthBytes = kmacOutputLengthBytes;
    }
}
