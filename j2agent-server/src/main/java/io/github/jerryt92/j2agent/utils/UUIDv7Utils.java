package io.github.jerryt92.j2agent.utils;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

/**
 * UUIDv7 生成工具类 (UUID Version 7 Generator Utility).
 * <p>
 * UUIDv7 是一种具备<b>时间趋势递增</b>特性的全新 UUID 标准。
 * 它的设计初衷是完美替代自增 ID 和传统的 UUIDv4，特别适合作为关系型数据库（如 MySQL InnoDB, PostgreSQL）的聚簇索引主键。
 * 它彻底解决了无序 UUID 在批量插入时引发的底层 B+ 树页分裂（Page Split）、碎片化以及间隙锁（Gap Lock）死锁问题。
 * </p>
 * * <h3>核心特性 (Key Features)</h3>
 * <ul>
 * <li><b>时间单调递增 (Time-Ordered):</b> 前 48 位固定为 Unix 毫秒级时间戳，保证生成的 UUID 随时间推移宏观递增。</li>
 * <li><b>极致的数据库性能 (DB-Index Friendly):</b> 在 B+ 树中如同自增 ID 一般总是向最右侧追加，消除写放大（Write Amplification）。</li>
 * <li><b>高防碰撞 (High Uniqueness):</b> 紧跟时间戳之后的 74 位使用安全随机数（或配合单毫秒内自增 Counter），保证分布式环境下的全局唯一性。</li>
 * <li><b>字典序完美对应 (Lexicographically Sortable):</b> 转为标准十六进制字符串（Hex）后，其字符串的排序顺序与生成的绝对时间顺序完全一致。</li>
 * </ul>
 * * <h3>标准规范 (Official Standard)</h3>
 * <p>
 * UUIDv7 现已作为 IETF 官方正式定稿的网络标准发布于 <b>RFC 9562</b>（该标准废弃并取代了旧版的 RFC 4122）。
 * </p>
 * <ul>
 * <li>规范名称: Universally Unique IDentifiers (UUIDs)</li>
 * <li>UUIDv7 章节: <a href="https://www.rfc-editor.org/rfc/rfc9562.html#name-uuid-version-7">RFC 9562 - Section 5.7. UUID Version 7</a></li>
 * </ul>
 *
 * @see UUID
 */
public class UUIDv7Utils {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    // 初始化一个全局单例的 UUIDv7 生成器
    private static final NoArgGenerator generator = Generators.timeBasedEpochGenerator();

    public static UUID randomUUIDv7Obj() {
        return generator.generate();
    }

    public static String randomUUIDv7() {
        // 生成 UUIDv7
        UUID uuidV7 = generator.generate();
        return encodeUUID(uuidV7);
    }

    public static String encodeUUID(UUID uuid) {
        // 申请 16 字节的缓冲区
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        // 依次放入高位和低位
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        // 转换为 Base64
        return safeEncode(bb.array());
    }

    public static String safeEncode(byte[] bytes) {
        return ENCODER.encodeToString(bytes)
                .replace('-', 'a')
                .replace('_', 'b');
    }
}