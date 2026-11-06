package com.gym.web.service;

import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.common.security.JwtUtil;
import com.gym.common.security.UserContext;
import com.gym.system.entity.Member;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.MemberMapper;
import com.gym.system.mapper.SysUserMapper;
import com.gym.web.dto.BindPhoneRequest;
import com.gym.web.dto.ChangePasswordRequest;
import com.gym.web.dto.RegisterRequest;
import com.gym.web.dto.ResetPasswordRequest;
import com.gym.web.dto.SmsLoginRequest;
import com.gym.web.security.Role;
import com.gym.web.security.TokenBlacklist;
import lombok.RequiredArgsConstructor;
import com.gym.web.security.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 登录、注册、验证码登录、找回密码、绑手机、改密与登出。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用户名：字母数字下划线，或纯手机号 */
    private static final Pattern USERNAME_OK = Pattern.compile("^[a-zA-Z0-9_]{3,32}$|^1\\d{10}$");

    private final SysUserMapper userMapper;
    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;
    private final SmsCodeService smsCodeService;

    public String login(String username, String password) {
        String account = username == null ? "" : username.trim();
        SysUser user = userMapper.selectByAccount(account);
        // 用户名不存在与密码错误返回同一句话，防账号枚举
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        assertActive(user);
        if (!StringUtils.hasText(user.getPassword())
                || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        return issueToken(user);
    }

    /** 手机号 + 验证码登录（无密码） */
    public String loginBySms(SmsLoginRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "参数不能为空");
        }
        String phone = SmsCodeService.normalizePhone(request.getPhone());
        smsCodeService.verifyOrThrow(phone, "login", request.getCode());
        SysUser user = findByPhoneFlexible(phone);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED,
                    "该手机号尚未注册或未绑定账号。请先用密码登录，在「我的 → 账号安全」绑定手机号");
        }
        assertActive(user);
        // 历史账号补 phone
        if (!StringUtils.hasText(user.getPhone())) {
            user.setPhone(phone);
            user.setUpdateTime(LocalDateTime.now());
            userMapper.updateById(user);
        }
        return issueToken(user);
    }

    /**
     * 发验证码前做业务校验（未注册 / 已占用），再走限流与存码。
     */
    public Map<String, Object> sendSms(String phone, String scene, String clientIp) {
        String p = SmsCodeService.normalizePhone(phone);
        String sc = scene == null ? "" : scene.trim().toLowerCase();
        switch (sc) {
            case "login", "reset" -> {
                if (findByPhoneFlexible(p) == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND,
                            "该手机号尚未注册或未绑定账号。请先用密码登录，在「我的 → 账号安全」绑定手机号后再用验证码");
                }
            }
            case "register" -> {
                if (userMapper.selectByPhone(p) != null || userMapper.selectByUsername(p) != null) {
                    throw new BusinessException(ResultCode.CONFLICT, "该手机号已注册，请直接登录");
                }
            }
            case "bind" -> {
                Long uid = UserContext.getUserId();
                if (uid == null) {
                    throw new BusinessException(ResultCode.UNAUTHORIZED, "绑定手机号请先登录");
                }
                SysUser me = userMapper.selectById(uid);
                if (me == null) {
                    throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
                }
                SysUser occupied = userMapper.selectByPhone(p);
                if (occupied != null && !occupied.getId().equals(uid)) {
                    throw new BusinessException(ResultCode.CONFLICT, "这个手机号已被其他账号绑定");
                }
                if (StringUtils.hasText(me.getPhone()) && me.getPhone().equals(p)) {
                    throw new BusinessException(ResultCode.BIZ_ERROR, "已经是这个手机号了，无需重复绑定");
                }
            }
            default -> throw new BusinessException(ResultCode.PARAM_ERROR,
                    "验证码场景只能是 login / register / reset / bind");
        }
        smsCodeService.send(p, sc, clientIp);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("hint", smsCodeService.isDemoSms()
                ? "演示环境验证码固定为 123456（也已打在服务端日志）"
                : "验证码已发送，5 分钟内有效");
        if (smsCodeService.isDemoSms()) {
            out.put("demoCode", "123456");
        }
        return out;
    }

    /**
     * 注册会员账号并直接登录（返回 JWT）。
     * 手机号注册需验证码；字母用户名可不绑手机（但找回密码会受影响）。
     */
    @Transactional
    public String register(RegisterRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "注册信息不能为空");
        }
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword();
        if (!USERNAME_OK.matcher(username).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "用户名用 3～32 位字母数字下划线，或 11 位手机号");
        }
        if (!StringUtils.hasText(password) || password.length() < 6) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码至少 6 位");
        }
        if (userMapper.selectByUsername(username) != null) {
            throw new BusinessException(ResultCode.CONFLICT, "这个用户名已经被占用");
        }

        boolean usernameIsPhone = username.matches("^1\\d{10}$");
        String phone = StringUtils.hasText(request.getPhone())
                ? SmsCodeService.normalizePhone(request.getPhone())
                : (usernameIsPhone ? username : null);

        if (phone != null) {
            smsCodeService.verifyOrThrow(phone, "register", request.getSmsCode());
            if (userMapper.selectByPhone(phone) != null) {
                throw new BusinessException(ResultCode.CONFLICT, "该手机号已注册，请直接登录");
            }
        } else if (usernameIsPhone) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "手机号注册需要验证码");
        }

        String display = StringUtils.hasText(request.getRealName())
                ? request.getRealName().trim()
                : username;
        if ("demo".equalsIgnoreCase(display)) {
            display = "铁友";
        }

        // member.phone 库里 NOT NULL + 联合唯一
        String memberPhone = phone != null
                ? phone
                : ("9" + String.format("%014d", System.nanoTime() % 100_000_000_000_000L));
        Member member = new Member();
        member.setName(display);
        member.setPhone(memberPhone);
        member.setGender(0);
        member.setGoal(3);
        member.setTrainLevel(1);
        member.setJoinDate(LocalDate.now());
        member.setStatus(1);
        member.setDeleted(0);
        try {
            memberMapper.insert(member);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.CONFLICT, "该手机号已注册，请直接登录");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRealName(display);
        user.setPhone(phone);
        user.setRole(Role.MEMBER.code());
        user.setMemberId(member.getId());
        user.setStatus(1);
        user.setDeleted(0);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);

        return issueToken(user);
    }

    /** 手机号验证码重置密码，成功后返回新 JWT（直接登进） */
    @Transactional
    public String resetPassword(ResetPasswordRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "参数不能为空");
        }
        String phone = SmsCodeService.normalizePhone(request.getPhone());
        smsCodeService.verifyOrThrow(phone, "reset", request.getCode());
        if (!StringUtils.hasText(request.getNewPassword()) || request.getNewPassword().length() < 6) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码至少 6 位");
        }
        SysUser user = findByPhoneFlexible(phone);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "该手机号尚未注册或未绑定账号。请先用密码登录并绑定手机号");
        }
        assertActive(user);
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        if (!StringUtils.hasText(user.getPhone())) {
            user.setPhone(phone);
        }
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        return issueToken(user);
    }

    /** 登录后绑定 / 换绑手机号（需验证码） */
    @Transactional
    public Map<String, Object> bindPhone(BindPhoneRequest request) {
        Long uidObj = UserContext.getUserId();
        if (uidObj == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        long uid = uidObj;
        SysUser me = userMapper.selectById(uid);
        if (me == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        assertActive(me);
        String phone = SmsCodeService.normalizePhone(request.getPhone());
        smsCodeService.verifyOrThrow(phone, "bind", request.getCode());

        SysUser occupied = userMapper.selectByPhone(phone);
        if (occupied != null && !occupied.getId().equals(uid)) {
            throw new BusinessException(ResultCode.CONFLICT, "这个手机号已被其他账号绑定");
        }

        me.setPhone(phone);
        me.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(me);

        if (me.getMemberId() != null) {
            Member member = memberMapper.selectById(me.getMemberId());
            if (member != null) {
                member.setPhone(phone);
                memberMapper.updateById(member);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phone", phone);
        out.put("phoneMasked", SmsCodeService.maskPhone(phone));
        out.put("bound", true);
        return out;
    }

    /** 登录后修改密码（需当前密码） */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long uidObj = UserContext.getUserId();
        if (uidObj == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        SysUser me = userMapper.selectById(uidObj);
        if (me == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        assertActive(me);
        if (!StringUtils.hasText(request.getOldPassword())
                || !passwordEncoder.matches(request.getOldPassword(), me.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "当前密码不对");
        }
        if (!StringUtils.hasText(request.getNewPassword()) || request.getNewPassword().length() < 6) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码至少 6 位");
        }
        if (passwordEncoder.matches(request.getNewPassword(), me.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与当前密码相同");
        }
        me.setPassword(passwordEncoder.encode(request.getNewPassword()));
        me.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(me);
    }

    /** 账号安全摘要：用户名、是否绑手机、掩码手机号 */
    public Map<String, Object> accountInfo() {
        Long uidObj = UserContext.getUserId();
        if (uidObj == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        SysUser me = userMapper.selectById(uidObj);
        if (me == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        boolean bound = StringUtils.hasText(me.getPhone());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", me.getUsername());
        out.put("realName", me.getRealName());
        out.put("phoneBound", bound);
        out.put("phone", bound ? me.getPhone() : null);
        out.put("phoneMasked", bound ? SmsCodeService.maskPhone(me.getPhone()) : null);
        out.put("hint", bound
                ? "已绑定手机，可用验证码登录 / 找回密码"
                : "尚未绑定手机号：验证码登录和找回密码不可用，建议现在绑定");
        return out;
    }

    public void logout(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "缺少 Authorization 头");
        }
        String jti = jwtUtil.parse(token).getId();
        tokenBlacklist.blacklist(jti, jwtUtil.remainingSeconds(token));
    }

    private SysUser findByPhoneFlexible(String phone) {
        SysUser user = userMapper.selectByPhone(phone);
        if (user == null) {
            user = userMapper.selectByUsername(phone);
        }
        return user;
    }

    private void assertActive(SysUser user) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
    }

    private String issueToken(SysUser user) {
        String display = StringUtils.hasText(user.getRealName()) ? user.getRealName().trim() : user.getUsername();
        if ("demo".equalsIgnoreCase(display)) {
            display = "铁友";
        }
        return jwtUtil.generate(user.getId(), user.getUsername(), normalizeRole(user.getRole()), display);
    }

    private int normalizeRole(Integer role) {
        return role == null ? Role.MEMBER.code() : role;
    }
}
