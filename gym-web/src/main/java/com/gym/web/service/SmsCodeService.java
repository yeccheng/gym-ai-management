package com.gym.web.service;

import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 短信验证码（毕设演示版）。
 * <ul>
 *   <li>默认演示码固定 {@code 123456}，答辩不用接真短信网关</li>
 *   <li>有 Redis 则存 Redis；没有则进程内 Map 兜底</li>
 *   <li>同号冷却 + 同号/同 IP 日限额，防刷</li>
 * </ul>
 */
@Slf4j
@Service
public class SmsCodeService {

    public static final Pattern PHONE_OK = Pattern.compile("^1\\d{10}$");

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(55);
    private static final Duration DAY_TTL = Duration.ofHours(36);

    private final StringRedisTemplate redis;
    private final Map<String, CodeEntry> local = new ConcurrentHashMap<>();

    /** true：永远发/验 123456，适合答辩；false：随机 6 位（仍打日志不发真短信） */
    @Value("${gym.auth.demo-sms:true}")
    private boolean demoSms;

    /** 同一手机号每天最多发多少条 */
    @Value("${gym.auth.sms-daily-limit:20}")
    private int dailyLimitPerPhone;

    /** 同一客户端 IP 每天最多发多少条 */
    @Value("${gym.auth.sms-ip-daily-limit:60}")
    private int dailyLimitPerIp;

    public SmsCodeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isDemoSms() {
        return demoSms;
    }

    public void send(String phone, String scene) {
        send(phone, scene, null);
    }

    public void send(String phone, String scene, String clientIp) {
        String p = normalizePhone(phone);
        String sc = normalizeScene(scene);
        String coolKey = "sms:cool:" + sc + ":" + p;
        if (hasKey(coolKey)) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "验证码刚发过，约 1 分钟后再试");
        }
        assertDailyQuota(p, clientIp);

        String code = demoSms ? "123456"
                : String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));
        put("sms:code:" + sc + ":" + p, code, TTL);
        put(coolKey, "1", SEND_COOLDOWN);
        bumpDaily(p, clientIp);
        // 不接运营商：日志可见即可答辩演示
        log.info("[短信演示] scene={} phone={} code={} demo={} ip={}",
                sc, maskPhone(p), code, demoSms, clientIp == null ? "-" : clientIp);
    }

    public void verifyOrThrow(String phone, String scene, String code) {
        String p = normalizePhone(phone);
        String sc = normalizeScene(scene);
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请输入验证码");
        }
        String expect = get("sms:code:" + sc + ":" + p);
        // 演示模式下始终接受 123456，避免 Redis 丢了卡死答辩
        boolean ok = code.trim().equals(expect)
                || (demoSms && "123456".equals(code.trim()));
        if (!ok) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "验证码错误或已过期");
        }
        // 用过即焚，防重放
        delete("sms:code:" + sc + ":" + p);
    }

    public static String normalizePhone(String phone) {
        String p = phone == null ? "" : phone.trim();
        if (!PHONE_OK.matcher(p).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请输入 11 位中国大陆手机号");
        }
        return p;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String normalizeScene(String scene) {
        String s = scene == null ? "" : scene.trim().toLowerCase();
        return switch (s) {
            case "login", "register", "reset", "bind" -> s;
            default -> throw new BusinessException(ResultCode.PARAM_ERROR,
                    "验证码场景只能是 login / register / reset / bind");
        };
    }

    private void assertDailyQuota(String phone, String clientIp) {
        long phoneCount = countOf("sms:day:phone:" + phone + ":" + LocalDate.now());
        if (phoneCount >= Math.max(1, dailyLimitPerPhone)) {
            throw new BusinessException(ResultCode.BIZ_ERROR,
                    "该手机号今天验证码次数已用完（每天最多 " + dailyLimitPerPhone + " 次），明天再试或用密码登录");
        }
        if (StringUtils.hasText(clientIp) && !"unknown".equalsIgnoreCase(clientIp)) {
            long ipCount = countOf("sms:day:ip:" + clientIp + ":" + LocalDate.now());
            if (ipCount >= Math.max(1, dailyLimitPerIp)) {
                throw new BusinessException(ResultCode.BIZ_ERROR,
                        "发送过于频繁，请稍后再试或换网络");
            }
        }
    }

    private void bumpDaily(String phone, String clientIp) {
        incr("sms:day:phone:" + phone + ":" + LocalDate.now());
        if (StringUtils.hasText(clientIp) && !"unknown".equalsIgnoreCase(clientIp)) {
            incr("sms:day:ip:" + clientIp + ":" + LocalDate.now());
        }
    }

    private void put(String key, String val, Duration ttl) {
        try {
            redis.opsForValue().set(key, val, ttl);
        } catch (Exception e) {
            local.put(key, new CodeEntry(val, System.currentTimeMillis() + ttl.toMillis()));
            log.debug("Redis 不可用，验证码改走本地缓存: {}", e.getMessage());
        }
    }

    private String get(String key) {
        try {
            String v = redis.opsForValue().get(key);
            if (v != null) {
                return v;
            }
        } catch (Exception ignored) {
            // fall through
        }
        CodeEntry e = local.get(key);
        if (e == null) {
            return null;
        }
        if (e.expireAt < System.currentTimeMillis()) {
            local.remove(key);
            return null;
        }
        return e.code;
    }

    private boolean hasKey(String key) {
        return get(key) != null;
    }

    private void delete(String key) {
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // ignore
        }
        local.remove(key);
    }

    private long countOf(String key) {
        String v = get(key);
        if (!StringUtils.hasText(v)) {
            return 0;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void incr(String key) {
        // 不用 redis.increment + expire：本机 Redis 连接实现里 expire/pExpire 会自递归打爆栈。
        // 直接 set + TTL（与验证码同路径），日限额略有并发误差可接受。
        long cur = countOf(key);
        put(key, String.valueOf(cur + 1), DAY_TTL);
    }

    private record CodeEntry(String code, long expireAt) {
    }
}
