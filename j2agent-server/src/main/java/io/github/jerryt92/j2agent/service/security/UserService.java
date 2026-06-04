package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.mapper.mgb.UserPoMapper;
import io.github.jerryt92.j2agent.constants.ErrorConstants;
import io.github.jerryt92.j2agent.model.RegisterRequestDto;
import io.github.jerryt92.j2agent.model.ResetPasswordRequestDto;
import io.github.jerryt92.j2agent.model.UserCreateRequestDto;
import io.github.jerryt92.j2agent.model.UserDto;
import io.github.jerryt92.j2agent.model.UserListDto;
import io.github.jerryt92.j2agent.model.UserPasswordUpdateRequestDto;
import io.github.jerryt92.j2agent.model.UserRoleUpdateRequestDto;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.model.po.mgb.UserPoExample;
import io.github.jerryt92.j2agent.model.security.SessionBo;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import io.github.jerryt92.j2agent.utils.UserUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * 用户管理服务。
 */
@Service
public class UserService {
    private static final String BUILTIN_ADMIN_USERNAME = "aiadmin";

    private final UserPoMapper userPoMapper;
    private final LoginService loginService;
    private final EmailVerificationService emailVerificationService;
    private final EmailRegisterService emailRegisterService;

    public UserService(UserPoMapper userPoMapper,
                       LoginService loginService,
                       EmailVerificationService emailVerificationService,
                       EmailRegisterService emailRegisterService) {
        this.userPoMapper = userPoMapper;
        this.loginService = loginService;
        this.emailVerificationService = emailVerificationService;
        this.emailRegisterService = emailRegisterService;
    }

    /**
     * 查询所有用户。
     */
    public UserListDto listUsers() {
        UserPoExample example = new UserPoExample();
        example.setOrderByClause("create_time DESC, username ASC");
        List<UserDto> users = userPoMapper.selectByExample(example).stream()
                .map(this::toDto)
                .toList();
        UserListDto dto = new UserListDto();
        dto.setData(users);
        return dto;
    }

    /**
     * 创建用户，当前模型仅允许管理员创建用户。
     */
    public UserDto createUser(UserCreateRequestDto request) {
        requireAdmin();
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
        }
        int role = normalizeRole(request.getRole());
        ensureUsernameAvailable(request.getUsername());

        UserPo user = new UserPo();
        user.setId(UUIDv7Utils.randomUUIDv7());
        user.setUsername(request.getUsername());
        user.setCreateTime(System.currentTimeMillis());
        user.setRole(role);
        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getPassword()));
        userPoMapper.insertSelective(user);
        return toDto(user);
    }

    /**
     * 删除普通用户，内置管理员不可删除。
     */
    public void deleteUser(String userId) {
        requireAdmin();
        UserPo user = requireUser(userId);
        ensureMutableUser(user);
        userPoMapper.deleteByPrimaryKey(userId);
        loginService.invalidateUserSessions(userId);
    }

    /**
     * 更新用户角色，保护内置管理员不被降权。
     */
    public void updateUserRole(UserRoleUpdateRequestDto request) {
        requireAdmin();
        if (request == null || isBlank(request.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        UserPo user = requireUser(request.getUserId());
        ensureMutableUser(user);
        user.setRole(normalizeRole(request.getRole()));
        userPoMapper.updateByPrimaryKeySelective(user);
        loginService.invalidateUserSessions(user.getId());
    }

    /**
     * 按规范化邮箱查找用户，不存在时返回 null。
     */
    public UserPo findByEmail(String email) {
        if (isBlank(email)) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        UserPoExample example = new UserPoExample();
        example.createCriteria().andEmailEqualTo(normalized);
        List<UserPo> users = userPoMapper.selectByExample(example);
        return users.isEmpty() ? null : users.getFirst();
    }

    /**
     * 邮箱找回密码：校验验证码后更新密码并失效会话。
     */
    public void resetPasswordByEmail(ResetPasswordRequestDto request) {
        if (request == null || isBlank(request.getEmail())
                || isBlank(request.getNewPassword()) || isBlank(request.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_FIELDS_REQUIRED);
        }
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        emailVerificationService.verifyAndConsumeReset(email, request.getCode());
        UserPo user = findByEmail(email);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.RESET_PASSWORD_CODE_INVALID);
        }
        ensureMutableUser(user);
        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getNewPassword()));
        userPoMapper.updateByPrimaryKeySelective(user);
        loginService.invalidateUserSessions(user.getId());
    }

    /**
     * 邮箱自助注册：校验验证码后创建普通用户，未传用户名时以邮箱作为登录名。
     */
    public void registerByEmail(RegisterRequestDto request) {
        if (request == null || isBlank(request.getPassword())
                || isBlank(request.getEmail()) || isBlank(request.getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_FIELDS_REQUIRED);
        }
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        emailRegisterService.requireEmailAllowed(email);
        String username = isBlank(request.getUsername()) ? email : request.getUsername().trim();
        emailVerificationService.verifyAndConsume(email, request.getCode());
        ensureUsernameAvailable(username);
        ensureEmailAvailable(email);

        UserPo user = new UserPo();
        user.setId(UUIDv7Utils.randomUUIDv7());
        user.setUsername(username);
        user.setEmail(email);
        user.setCreateTime(System.currentTimeMillis());
        user.setRole(SessionBo.RoleEnum.USER.getValue());
        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getPassword()));
        userPoMapper.insertSelective(user);
    }

    /**
     * 管理员可重置他人密码，普通用户仅可凭旧密码修改自己的密码。
     */
    public void updateUserPassword(UserPasswordUpdateRequestDto request) {
        if (request == null || isBlank(request.getNewPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newPassword is required");
        }
        SessionBo session = requireSession();
        String targetUserId = isBlank(request.getUserId()) ? session.getUserId() : request.getUserId();
        UserPo user = requireUser(targetUserId);

        if (!session.isAdmin()) {
            if (!session.getUserId().equals(targetUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cannot modify another user's password");
            }
            if (isBlank(request.getOldPassword()) || !UserUtil.verifyPassword(user.getId(), request.getOldPassword(), user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "old password is invalid");
            }
        } else if (!session.getUserId().equals(targetUserId)) {
            ensureMutableUser(user);
        }

        user.setPasswordHash(UserUtil.getPasswordHash(user.getId(), request.getNewPassword()));
        userPoMapper.updateByPrimaryKeySelective(user);
        loginService.invalidateUserSessions(user.getId());
    }

    private SessionBo requireSession() {
        SessionBo session = loginService.getSession();
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
        return session;
    }

    private void requireAdmin() {
        if (!requireSession().isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required");
        }
    }

    private UserPo requireUser(String userId) {
        if (isBlank(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        UserPo user = userPoMapper.selectByPrimaryKey(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        return user;
    }

    private void ensureUsernameAvailable(String username) {
        UserPoExample example = new UserPoExample();
        example.createCriteria().andUsernameEqualTo(username);
        if (userPoMapper.countByExample(example) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_USERNAME_EXISTS);
        }
    }

    private void ensureEmailAvailable(String email) {
        UserPoExample example = new UserPoExample();
        example.createCriteria().andEmailEqualTo(email);
        if (userPoMapper.countByExample(example) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_EMAIL_EXISTS);
        }
    }

    private void ensureMutableUser(UserPo user) {
        if (BUILTIN_ADMIN_USERNAME.equals(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "builtin admin cannot be modified");
        }
    }

    private int normalizeRole(Integer role) {
        int value = role == null ? SessionBo.RoleEnum.USER.getValue() : role;
        if (value != SessionBo.RoleEnum.ADMIN.getValue() && value != SessionBo.RoleEnum.USER.getValue()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported role");
        }
        return value;
    }

    private UserDto toDto(UserPo user) {
        UserDto dto = new UserDto();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setRole(user.getRole());
        dto.setCreateTime(user.getCreateTime());
        dto.setEmail(user.getEmail());
        return dto;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
