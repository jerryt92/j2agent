package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.model.PowCaptchaResp;
import io.github.jerryt92.j2agent.model.SlideCaptchaResp;
import io.github.jerryt92.j2agent.model.Track;
import io.github.jerryt92.j2agent.utils.HashUtil;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@EnableScheduling
public class CaptchaService {
    private static final Logger log = LogManager.getLogger(CaptchaService.class);
    final Font iFont;
    // 滑动验证码图片与凹槽尺寸
    private static final int SLIDE_CAPTCHA_WIDTH = 400;
    private static final int SLIDE_CAPTCHA_HEIGHT = 300;
    private static final int SLIDE_CAPTCHA_SLIDER_SIZE = 50;
    Base64.Encoder encoder = Base64.getEncoder();
    private static final Long CAPTCHA_EXPIRE_TIME = 60L;

    private static final String CAPTCHA_KEY_PREFIX = "security_captcha:";

    @Value("${j2agent.security.captcha.pow-difficulty}")
    private int powDifficulty;

    private static class CaptchaCache {
        String code;
        Float puzzleX;
        String powSalt;
        Integer powDifficulty;
        long expireTime;
    }

    private static final ConcurrentHashMap<String, CaptchaCache> captchaCacheMap = new ConcurrentHashMap<>();

    public CaptchaService() {
        iFont = new Font("Arial", Font.PLAIN, 12);
    }

    /**
     * 生成凹槽背景和滑块图像
     */
    public SlideCaptchaResp genSlideCaptcha() {
        // 凹槽背景图像
        BufferedImage puzzleImage = null;
        try {
            // 获取资源目录下所有图片文件名
            String imagesPath = "/static/captcha_images";
            URL dirURL = getClass().getResource(imagesPath);
            if (dirURL != null) {
                File dir = new File(dirURL.toURI());
                File[] files = dir.listFiles((d, name) -> name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"));
                if (files != null && files.length > 0) {
                    // 随机选取一个文件
                    File randomFile = files[(int) (Math.random() * files.length)];
                    try (InputStream bgStream = new FileInputStream(randomFile)) {
                        puzzleImage = ImageIO.read(bgStream);
                        if (puzzleImage != null) {
                            // 如果图片尺寸不符，缩放
                            if (puzzleImage.getWidth() != SLIDE_CAPTCHA_WIDTH || puzzleImage.getHeight() != SLIDE_CAPTCHA_HEIGHT) {
                                BufferedImage scaled = new BufferedImage(SLIDE_CAPTCHA_WIDTH, SLIDE_CAPTCHA_HEIGHT, BufferedImage.TYPE_INT_RGB);
                                Graphics2D g2d = scaled.createGraphics();
                                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                                g2d.drawImage(puzzleImage, 0, 0, SLIDE_CAPTCHA_WIDTH, SLIDE_CAPTCHA_HEIGHT, null);
                                g2d.dispose();
                                puzzleImage = scaled;
                            }
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            log.error("", e);
        }
        if (puzzleImage == null) {
            puzzleImage = generateTextTextureBackground(null, SLIDE_CAPTCHA_WIDTH, SLIDE_CAPTCHA_HEIGHT, null, iFont);
        }
//        puzzleImage = generateTextTextureBackground(puzzleImage, SLIDE_CAPTCHA_WIDTH, SLIDE_CAPTCHA_HEIGHT, "", iFont);
        String code = UUIDv7Utils.randomUUIDv7();
        BufferedImage sliderImage = new BufferedImage(SLIDE_CAPTCHA_SLIDER_SIZE, SLIDE_CAPTCHA_SLIDER_SIZE, BufferedImage.TYPE_INT_ARGB);
        // 背景
        Graphics2D puzzleG = puzzleImage.createGraphics();
        // 滑块
        Graphics2D sliderG = sliderImage.createGraphics();
        try {
            // 设置抗锯齿
            puzzleG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sliderG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 随机生成凹槽位置
            float x = (float) ((0.7 * Math.random() + 0.2) * (SLIDE_CAPTCHA_WIDTH - SLIDE_CAPTCHA_SLIDER_SIZE));
            float y = (float) ((0.8 * Math.random() + 0.1) * (SLIDE_CAPTCHA_HEIGHT - SLIDE_CAPTCHA_SLIDER_SIZE));
            // 创建凹槽路径
            GeneralPath puzzlePath = createPuzzlePath(puzzleG, x, y, SLIDE_CAPTCHA_SLIDER_SIZE);
            // 绘制凹槽
            drawPuzzle(puzzleG, puzzlePath);
            // 绘制滑块
            drawSlider(sliderG, puzzleImage, x, y, SLIDE_CAPTCHA_SLIDER_SIZE, puzzlePath);
            // 填充凹槽
            fillPuzzle(puzzleG, puzzlePath);
            // 保存图片
            SlideCaptchaResp slideCaptchaResp = new SlideCaptchaResp();
            slideCaptchaResp.setPuzzleUrl("data:image/png;base64," + encoder.encodeToString(bufferedImageToByteArray(puzzleImage, "png")));
            slideCaptchaResp.setWidth(SLIDE_CAPTCHA_WIDTH);
            slideCaptchaResp.setHeight(SLIDE_CAPTCHA_HEIGHT);
            slideCaptchaResp.setSliderUrl("data:image/png;base64," + encoder.encodeToString(bufferedImageToByteArray(sliderImage, "png")));
            slideCaptchaResp.setSliderSize(SLIDE_CAPTCHA_SLIDER_SIZE);
            slideCaptchaResp.setSliderY(y);
            String hash = HashUtil.getMessageDigest(bufferedImageToByteArray(puzzleImage, "png"), HashUtil.MdAlgorithm.SHA1);
            slideCaptchaResp.setHash((hash));
            CaptchaCache captchaCache = new CaptchaCache();
            captchaCache.code = code;
            captchaCache.puzzleX = x;
            captchaCache.expireTime = System.currentTimeMillis() + CAPTCHA_EXPIRE_TIME * 1000;
            captchaCacheMap.put(CAPTCHA_KEY_PREFIX + hash, captchaCache);
            return slideCaptchaResp;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } finally {
            puzzleG.dispose();
            sliderG.dispose();
        }
    }

    /**
     * 生成 PoW 验证挑战
     */
    public PowCaptchaResp genPowCaptcha() {
        String hash = UUIDv7Utils.randomUUIDv7();
        String code = UUIDv7Utils.randomUUIDv7();
        String powSalt = UUIDv7Utils.randomUUIDv7();

        CaptchaCache captchaCache = new CaptchaCache();
        captchaCache.code = code;
        captchaCache.powSalt = powSalt;
        captchaCache.powDifficulty = powDifficulty;
        captchaCache.expireTime = System.currentTimeMillis() + CAPTCHA_EXPIRE_TIME * 1000;
        captchaCacheMap.put(CAPTCHA_KEY_PREFIX + hash, captchaCache);

        PowCaptchaResp powCaptchaResp = new PowCaptchaResp();
        powCaptchaResp.setHash(hash);
        powCaptchaResp.setPowSalt(powSalt);
        powCaptchaResp.setPowDifficulty(powDifficulty);
        return powCaptchaResp;
    }

    /**
     * 验证滑块验证码
     *
     * @param sliderX 用户拖动的最终X坐标 (前端传来的结果值)
     * @param hash    验证码唯一标识
     * @param track   行为轨迹数组
     * @return 验证成功返回 code，失败返回 null
     */
    public String verifySlideCaptchaGetCaptchaCode(Float sliderX, String hash, Track[] track) {
        if (sliderX == null || hash == null || track == null) {
            return null;
        }
        // 1. 缓存层校验 (先判断是否存在，过期逻辑)
        CaptchaCache captchaCache = captchaCacheMap.get(CAPTCHA_KEY_PREFIX + hash);
        if (captchaCache == null) {
            return null;
        }
        // 2. 校验过期时间
        if (System.currentTimeMillis() > captchaCache.expireTime) {
            captchaCacheMap.remove(CAPTCHA_KEY_PREFIX + hash);
            return null;
        }
        // 3. 核心：行为轨迹算法校验
        if (!validateTrack(track)) {
            log.warn("滑块轨迹校验失败: hash={}", hash);
            return null;
        }

        try {
            String code = captchaCache.code;
            Float puzzleX = captchaCache.puzzleX;

            // puzzleX != null 说明还没被验证过
            if (null != puzzleX) {
                // 4. 校验最终结果准确度 (允许 5px 误差)
                if (Math.abs(sliderX - puzzleX) < 5) {
                    // 标记为“已验证”：将 puzzleX 置空 (根据你的业务逻辑)
                    captchaCache.puzzleX = null;
                    // 更新缓存
                    captchaCacheMap.put(CAPTCHA_KEY_PREFIX + hash, captchaCache);
                    log.info("滑块验证成功: hash={}", hash);
                    return code;
                }
            } else {
                // 如果 puzzleX 已经是 null，说明之前已经验证通过了，防止重复使用旧 Token
                return code;
            }
            // 验证失败，移除缓存，强制重刷
            captchaCacheMap.remove(CAPTCHA_KEY_PREFIX + hash);
        } catch (Throwable t) {
            log.error("验证异常", t);
        }
        return null;
    }

    /**
     * 纯 PoW 验证并获取验证码 code
     */
    public String verifyPowCaptchaGetCaptchaCode(String hash, String powNonce) {
        if (hash == null || powNonce == null || powNonce.isEmpty()) {
            return null;
        }
        CaptchaCache captchaCache = captchaCacheMap.get(CAPTCHA_KEY_PREFIX + hash);
        if (captchaCache == null) {
            return null;
        }
        if (System.currentTimeMillis() > captchaCache.expireTime) {
            captchaCacheMap.remove(CAPTCHA_KEY_PREFIX + hash);
            return null;
        }
        if (!validatePow(hash, captchaCache, powNonce)) {
            captchaCacheMap.remove(CAPTCHA_KEY_PREFIX + hash);
            return null;
        }
        return captchaCache.code;
    }

    /**
     * 行为校验算法
     * * @param track 轨迹数组
     */
    private boolean validateTrack(Track[] track) {
        if (track == null || track.length < 5) {
            // 轨迹点过少（<5），基本可以判定为脚本直接调用接口
            log.warn("validateTrack 失败: 轨迹点过少 len={}", track == null ? 0 : track.length);
            return false;
        }
        Track first = track[0];
        Track last = track[track.length - 1];
        // --- 1. 基础物理规则校验 ---
        // 时间校验：极速不低于 200ms (给予一定宽容度设为 100ms)，也不应停顿太久
        long totalTime = last.getT() - first.getT();
        if (totalTime < 100 || totalTime > 10000) {
            log.warn("validateTrack 失败: 总耗时异常 {}ms", totalTime);
            return false;
        }
        // --- 2. 统计特征分析 ---
        float sumYChange = 0; // Y轴累计变化量
        float maxSpeed = 0;   // 最大速度
        double sumSpeed = 0;  // 速度总和
        int speedSamples = 0; // 速度采样数
        // 记录倒数 1/4 阶段的速度，用于检测减速行为
        double lastStageSpeedSum = 0;
        int lastStageCount = 0;
        int startCheckIndex = (int) (track.length * 0.75);
        for (int i = 1; i < track.length; i++) {
            Track cur = track[i];
            Track prev = track[i - 1];
            // 时间倒流校验
            if (cur.getT() < prev.getT()) {
                return false;
            }
            float dt = cur.getT() - prev.getT();
            float dx = cur.getPointerX() - prev.getPointerX();
            float dy = cur.getPointerY() - prev.getPointerY();
            // 累加 Y 轴抖动 (取绝对值)
            sumYChange += Math.abs(dy);
            // 速度计算 (px/ms -> px/s)
            if (dt > 0) {
                float speed = Math.abs(dx) / dt * 1000f;
                // 极速校验：速率通常不会超过 5000px/s (鼠标甩动除外，但滑块场景较少)
                // 适当放宽防止误判，但如果出现瞬间移动 (速度无穷大) 则为异常
                if (speed > 20000) {
                    log.warn("validateTrack 失败: 瞬移异常 speed={}", speed);
                    return false;
                }
                // 只统计正向移动的速度
                if (dx > 0) {
                    maxSpeed = Math.max(maxSpeed, speed);
                    sumSpeed += speed;
                    speedSamples++;
                    // 统计末端速度
                    if (i > startCheckIndex) {
                        lastStageSpeedSum += speed;
                        lastStageCount++;
                    }
                }
            }
        }
        // --- 3. 核心逻辑判定 ---
        // A. Y轴死直线校验 (最有效的简单脚本防御)
        // 真实很难保持 Y 轴完全不动（sumYChange == 0）。
        // 设置一个极低的阈值，比如 0，或者如果做了平滑处理设为 1。
        if (sumYChange == 0) {
            log.warn("validateTrack 失败: Y轴无抖动 (机器特征)");
            return false;
        }
        // B. 匀速校验 (平均速度 vs 最大速度)
        if (speedSamples > 0) {
            double avgSpeed = sumSpeed / speedSamples;
            // 如果最大速度极其接近平均速度，说明是匀速运动 (var -> 0)
            // 人类运动：Max 远大于 Avg (因为有起步和停止过程)
            if (maxSpeed < avgSpeed * 1.1) {
                log.warn("validateTrack 失败: 过于匀速 (机器特征) max={}, avg={}", maxSpeed, avgSpeed);
                return false;
            }
        }
        // C. 减速进坑 (Fitts Law)
        // 绝大多数人在接近终点时会减速对齐。
        // 如果最后 25% 路程的平均速度，依然等于或大于整体平均速度，甚至接近最大速度，可疑。
        /* 注意：此规则有一定误判率（比如极快手速的人），
           建议作为权重因子，或者设置较宽松的阈值 (e.g., 末端速度是整体均速的 2倍以上则报警)
        */
        if (lastStageCount > 0 && speedSamples > 0) {
            double lastStageAvg = lastStageSpeedSum / lastStageCount;
            double totalAvg = sumSpeed / speedSamples;
            if (lastStageAvg > totalAvg * 2.5) {
                log.warn("validateTrack 失败: 末端异常加速)");
                return false;
            }
        }
        return true;
    }

    private boolean validatePow(String hash, CaptchaCache captchaCache, String powNonce) {
        if (powNonce.length() > 128 || captchaCache.powSalt == null || captchaCache.powDifficulty == null) {
            return false;
        }
        try {
            String payload = hash + ":" + captchaCache.powSalt + ":" + powNonce;
            String digest = HashUtil.getMessageDigest(payload.getBytes(StandardCharsets.UTF_8), HashUtil.MdAlgorithm.SHA256);
            return digest.startsWith(repeatZero(captchaCache.powDifficulty));
        } catch (NoSuchAlgorithmException e) {
            log.error("PoW digest 算法异常", e);
            return false;
        }
    }

    private String repeatZero(int n) {
        if (n <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append('0');
        }
        return sb.toString();
    }

    /**
     * 验证滑块通过后得到的的code（登录时校验）
     *
     * @param captchaCode
     * @param hash
     * @return
     */
    public boolean verifyCaptchaCode(String captchaCode, String hash) {
        if (null == captchaCode || null == hash) {
            return false;
        }
        CaptchaCache captchaCache = captchaCacheMap.remove(CAPTCHA_KEY_PREFIX + hash);
        try {
            if (null != captchaCache) {
                if (System.currentTimeMillis() > captchaCache.expireTime) {
                    return false;
                }
                String code = captchaCache.code;
                if (null != code) {
                    if (captchaCode.equalsIgnoreCase(code)) {
                        captchaCacheMap.remove(CAPTCHA_KEY_PREFIX + hash);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            log.error("", t);
        }
        return false;
    }

    /**
     * 生成带有字符和彩色噪声纹理的背景图
     *
     * @param image  背景图
     * @param width  背景图宽度
     * @param height 背景图高度
     * @param text   纹理文本
     * @param font   使用的字体
     * @return 带有纹理的 BufferedImage
     */
    private BufferedImage generateTextTextureBackground(BufferedImage image, int width, int height, String text, Font font) {
        if (text == null) {
            text = "";
        }
        Graphics2D g2d;
        if (image == null) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            g2d = image.createGraphics();
            // 设置抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 设置背景颜色
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, width, height);
        } else {
            g2d = image.createGraphics();
        }
        // 随机选择文本颜色
        Color randomColor = new Color((int) (Math.random() * 0x1000000));
        g2d.setColor(randomColor);
        // 随机选择字体样式和大小
        int fontStyle = (int) (Math.random() * 4); // 随机选择字体样式 (0-PLAIN, 1-BOLD, 2-ITALIC, 3-BOLD+ITALIC)
        int fontSize = (int) ((double) height / 6 + Math.random() * 20) + 20; // 随机字体大小 (20-40)
        Font newFont = new Font(font.getName(), fontStyle, fontSize);
        g2d.setFont(newFont);
        // 获取字体的宽度和高度
        int textWidth = g2d.getFontMetrics().stringWidth(text);
        int textHeight = g2d.getFontMetrics().getHeight();
        // 随机生成文本位置
        int x = (int) (Math.random() * (width - textWidth)); // 随机水平位置
        int y = (int) (Math.random() * (height - textHeight)) + textHeight; // 随机垂直位置
        // 绘制文本
        g2d.drawString(text, x, y);
        // 添加彩色噪声纹理
        addNoiseTexture(g2d, width, height);
        g2d.dispose();
        return image;
    }

    private void addNoiseTexture(Graphics2D g2d, int width, int height) {
        // 添加彩色点
        for (int i = 0; i < 200; i++) { // 增加点的数量
            int x = (int) (Math.random() * width);
            int y = (int) (Math.random() * height);
            Color randomColor = new Color((int) (Math.random() * 0x1000000));
            g2d.setColor(randomColor);
            int diameter = 2 + (int) (Math.random() * 1); // 点的大小在 3-5 像素之间
            g2d.fillOval(x, y, diameter, diameter); // 使用圆形点
        }
        // 添加随机颜色的线条
        for (int i = 0; i < 20; i++) { // 增加线条数量
            int x1 = (int) (Math.random() * width);
            int y1 = (int) (Math.random() * height);
            int x2 = (int) (Math.random() * width);
            int y2 = (int) (Math.random() * height);
            Color randomColor = new Color((int) (Math.random() * 0x1000000));
            g2d.setColor(randomColor);
            g2d.drawLine(x1, y1, x2, y2);
        }
    }

    private static GeneralPath createPuzzlePath(Graphics2D g, float x, float y, float l) {
        // 绘制凹槽路径
        g.setColor(Color.darkGray);
        g.setStroke(new BasicStroke(2));
        // 创建凹槽路径
        GeneralPath path = new GeneralPath();
        // 凹陷程度
        int deep = 4;
        // 顶部边缘
        path.moveTo(x, y);
        path.lineTo(x + l / 3, y);
        path.quadTo(x + l / 2, y + l / deep, x + (2 * l) / 3, y); // 向下凹陷
        path.lineTo(x + l, y);
        // 右侧边缘
        path.lineTo(x + l, y + l / 3);
//        path.quadTo(x + l - l / deep, y + l / 2, x + l, y + (2 * l) / 3); // 向左凹陷
        path.lineTo(x + l, y + l);
        // 底部边缘
        path.lineTo(x + (2 * l) / 3, y + l);
//        path.quadTo(x + l / 2, y + l - l / deep, x + l / 3, y + l); // 向上凹陷
        path.lineTo(x, y + l);
        // 左侧边缘
        path.lineTo(x, y + (2 * l) / 3);
        path.quadTo(x + l / deep, y + l / 2, x, y + l / 3); // 向右凹陷
        path.closePath();
        return path;
    }

    private static void drawPuzzle(Graphics2D g, GeneralPath path) {
        // 绘制凹槽边框
        g.setStroke(new BasicStroke(5));
        g.setColor(Color.ORANGE);
        g.draw(path);
    }

    private static void drawSlider(Graphics2D g, BufferedImage source, float x, float y, int size, GeneralPath path) {
        // 创建一个全透明的图像
        BufferedImage slider = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sliderG = slider.createGraphics();
        try {
            sliderG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 填充为全透明
            sliderG.setComposite(AlphaComposite.Clear);
            sliderG.fillRect(0, 0, size, size);
            // 设置为只绘制路径内的内容
            sliderG.setComposite(AlphaComposite.Src);
            // 将路径移动到 (0,0) 坐标（因为 subImage 起点是 (x,y)，而 slider 图像坐标是 (0,0)）
            GeneralPath movedPath = (GeneralPath) path.clone();
            movedPath.transform(java.awt.geom.AffineTransform.getTranslateInstance(-x, -y));
            sliderG.setClip(movedPath);
            // 从原图像裁剪出指定区域并绘制
            BufferedImage subImage = source.getSubimage((int) x, (int) y, size, size);
            sliderG.drawImage(subImage, 0, 0, null);
            // 绘制边框
            sliderG.setClip(null);
            sliderG.setStroke(new BasicStroke(2));
            sliderG.setColor(Color.GRAY);
            sliderG.draw(movedPath);
            // 将 slider 图像画到目标 g 上
            g.drawImage(slider, 0, 0, null);
        } finally {
            sliderG.dispose();
        }
    }

    private static void fillPuzzle(Graphics2D g, GeneralPath path) {
        // 填充凹槽
        g.setColor(new Color(224, 232, 245));
        g.fill(path);
    }

    private static byte[] bufferedImageToByteArray(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, format, byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void clearExpiredCaptchaCache() {
        try {
            captchaCacheMap.entrySet().removeIf(entry -> entry.getValue().expireTime < System.currentTimeMillis());
        } catch (Throwable t) {
            log.error("", t);
        }
    }
}
