package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.model.security.SessionBo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import io.github.jerryt92.j2agent.utils.UserUtil;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@EnableScheduling
public class LoginService {
    private static final ConcurrentHashMap<String, SessionBo> SESSION_MAP = new ConcurrentHashMap<>();
    private static final long EXPIRE_TIME_MINUTES = 30;
    private final UserPoMapper userPoMapper;
    private final CaptchaService captchaService;
    private final ThreadLocal<String> sessionThreadLocal = new ThreadLocal<>();

    public LoginService(UserPoMapper userPoMapper, CaptchaService captchaService) {
        this.userPoMapper = userPoMapper;
        this.captchaService = captchaService;
    }

    public SessionBo login(String username, String password, String captchaCode, String hash) {
        UserPoExample example = new UserPoExample();
        example.createCriteria().andUsernameEqualTo(username);
        if (!captchaService.verifyCaptchaCode(captchaCode, hash)) {
            return null;
        }
        List<UserPo> userPos = userPoMapper.selectByExample(example);
        if (userPos.isEmpty()) {
            return null;
        }
        UserPo userPo = userPos.get(0);
        if (UserUtil.verifyPassword(userPo.getId(), password, userPo.getPasswordHash())) {
            SessionBo sessionBo = new SessionBo();
            sessionBo.setSessionId(UUIDv7Utils.randomUUIDv7());
            sessionBo.setUserId(userPo.getId());
            sessionBo.setUsername(userPo.getUsername());
            sessionBo.setRole(SessionBo.RoleEnum.fromValue(userPo.getRole()));
            sessionBo.setExpireTime(System.currentTimeMillis() + 1000 * 60 * 60 * 24);
            SESSION_MAP.put(sessionBo.getSessionId(), sessionBo);
            return sessionBo;
        } else {
            return null;
        }
    }

    public SessionBo getSession(String sessionId) {
        return SESSION_MAP.get(sessionId);
    }

    public SessionBo getSession() {
        String sessionId = this.sessionThreadLocal.get();
        return getSession(sessionId);
    }

    /**
     * 绑定当前请求的会话 ID，供业务服务读取登录用户。
     */
    public void bindSession(String sessionId) {
        this.sessionThreadLocal.set(sessionId);
    }

    /**
     * 清理当前线程会话，避免线程复用串号。
     */
    public void clearSession() {
        this.sessionThreadLocal.remove();
    }

    public void logout(String sessionId) {
        SESSION_MAP.remove(sessionId);
    }

    /**
     * 用户信息变更后使其旧会话失效。
     */
    public void invalidateUserSessions(String userId) {
        SESSION_MAP.entrySet().removeIf(entry -> userId.equals(entry.getValue().getUserId()));
    }

    public boolean validateSession(String sessionId) {
        boolean result = false;
        SessionBo sessionBo = SESSION_MAP.get(sessionId);
        if (sessionBo != null) {
            if (sessionBo.getExpireTime() > System.currentTimeMillis()) {
                sessionBo.setExpireTime(System.currentTimeMillis() + EXPIRE_TIME_MINUTES * 60 * 1000);
                result = true;
            }
        }
        return result;
    }

    /**
     * 定时任务，清理session
     */
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void cleanSession() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SessionBo> entry : SESSION_MAP.entrySet()) {
            if (entry.getValue().getExpireTime() < now) {
                SESSION_MAP.remove(entry.getKey());
            }
        }
    }
}
