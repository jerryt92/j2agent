package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.config.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.constants.ErrorConstants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

/**
 * 邮箱注册验证码：Redis 存储与校验。
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 限流窗口内同一邮箱最多发送次数 */
    private static final int MAX_SEND_PER_WINDOW = 5;
    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final String codeKeyPrefix;
    private final String rateKeyPrefix;
    private final String resetCodeKeyPrefix;
    private final String resetRateKeyPrefix;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(StringRedisTemplate redisTemplate, RedisKeyNamespaces redisKeyNamespaces) {
        this.redisTemplate = redisTemplate;
        this.codeKeyPrefix = redisKeyNamespaces.key("email-reg:code:");
        this.rateKeyPrefix = redisKeyNamespaces.key("email-reg:rate:");
        this.resetCodeKeyPrefix = redisKeyNamespaces.key("email-reset:code:");
        this.resetRateKeyPrefix = redisKeyNamespaces.key("email-reset:rate:");
    }

    /**
     * 生成并缓存验证码；同一邮箱在 60 秒窗口内最多连发 5 次，第 6 次起返回 429。
     */
    public String issueCode(String email) {
        String normalized = normalizeEmail(email);
        try {
            assertSendAllowed(normalized);
            String code = String.format("%06d", random.nextInt(1_000_000));
            redisTemplate.opsForValue().set(codeKeyPrefix + normalized, code, CODE_TTL);
            return code;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("redis unavailable when issuing email code", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ErrorConstants.COMMON_REDIS_UNAVAILABLE);
        }
    }

    /**
     * 生成并缓存找回密码验证码；限流规则与注册相同。
     */
    public String issueResetCode(String email) {
        String normalized = normalizeResetEmail(email);
        try {
            assertResetSendAllowed(normalized);
            String code = String.format("%06d", random.nextInt(1_000_000));
            redisTemplate.opsForValue().set(resetCodeKeyPrefix + normalized, code, CODE_TTL);
            return code;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("redis unavailable when issuing reset password code", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ErrorConstants.COMMON_REDIS_UNAVAILABLE);
        }
    }

    /**
     * 校验找回密码验证码，成功后删除。
     */
    public void verifyAndConsumeReset(String email, String code) {
        String normalized = normalizeResetEmail(email);
        if (StringUtils.isBlank(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_CODE_REQUIRED);
        }
        try {
            String key = resetCodeKeyPrefix + normalized;
            String stored = redisTemplate.opsForValue().get(key);
            if (stored == null || !stored.equals(code.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_CODE_INVALID);
            }
            redisTemplate.delete(key);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("redis unavailable when verifying reset password code", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ErrorConstants.COMMON_REDIS_UNAVAILABLE);
        }
    }

    /**
     * 校验验证码，成功后删除。
     */
    public void verifyAndConsume(String email, String code) {
        String normalized = normalizeEmail(email);
        if (StringUtils.isBlank(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_CODE_REQUIRED);
        }
        try {
            String key = codeKeyPrefix + normalized;
            String stored = redisTemplate.opsForValue().get(key);
            if (stored == null || !stored.equals(code.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_CODE_INVALID);
            }
            redisTemplate.delete(key);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("redis unavailable when verifying email code", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ErrorConstants.COMMON_REDIS_UNAVAILABLE);
        }
    }

    /**
     * 检查发送次数：窗口内前 5 次放行，第 6 次起拒绝。
     */
    private void assertSendAllowed(String normalizedEmail) {
        assertSendAllowed(normalizedEmail, rateKeyPrefix, ErrorConstants.REGISTER_RATE_LIMIT);
    }

    private void assertResetSendAllowed(String normalizedEmail) {
        assertSendAllowed(normalizedEmail, resetRateKeyPrefix, ErrorConstants.RESET_PASSWORD_RATE_LIMIT);
    }

    private void assertSendAllowed(String normalizedEmail, String rateKeyPrefix, String rateLimitError) {
        String rateKey = rateKeyPrefix + normalizedEmail;
        Long count = redisTemplate.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redisTemplate.expire(rateKey, RATE_WINDOW);
        }
        if (count != null && count > MAX_SEND_PER_WINDOW) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, rateLimitError);
        }
    }

    private String normalizeResetEmail(String email) {
        if (StringUtils.isBlank(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_EMAIL_REQUIRED);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEmail(String email) {
        if (StringUtils.isBlank(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_EMAIL_REQUIRED);
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
