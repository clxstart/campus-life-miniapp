package com.campuslife.service;

import com.campuslife.mapper.UserBlockMapper;
import com.campuslife.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户拉黑。
 *
 * 语义：**双向不可见**（业界主流做法）
 *   - A 拉黑 B 后，A 的帖子和评论流里不再出现 B；
 *   - B 那边同样看不到 A（否则 B 可以继续单向骚扰）；
 *   - 双方无法互发私信。
 *
 * 为什么做了缓存：帖子流每次请求都要过滤，直接查库会多一次往返。
 * 这里用 30 秒本地缓存；拉黑/解除时主动失效，做到"操作立即生效"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBlockService {

    private static final long TTL_MS = 30_000L;

    private final UserBlockMapper blockMapper;
    private final UserMapper userMapper;

    private record CacheEntry(List<Long> ids, long at) {}

    private final Map<Long, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 与我互相不可见的用户 id（含我拉黑的和拉黑我的）。 */
    public List<Long> hiddenUserIds(Long userId) {
        if (userId == null || userId < 1) return List.of();
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(userId);
        if (cached != null && now - cached.at() < TTL_MS) return cached.ids();
        List<Long> ids;
        try {
            ids = blockMapper.hiddenUserIds(userId);
        } catch (Exception e) {
            log.warn("读取拉黑列表失败，本次不过滤 userId={}: {}", userId, e.getMessage());
            ids = List.of();
        }
        if (ids == null) ids = List.of();
        cache.put(userId, new CacheEntry(ids, now));
        if (cache.size() > 3000) {
            cache.entrySet().removeIf(en -> now - en.getValue().at() >= TTL_MS);
        }
        return ids;
    }

    /** 两人之间是否存在拉黑关系（任一方拉黑都算）。 */
    public boolean blockedBetween(Long userA, Long userB) {
        if (userA == null || userB == null || userA.equals(userB)) return false;
        // 双向都判断：A 拉黑 B 或 B 拉黑 A
        return hiddenUserIds(userA).contains(userB) || hiddenUserIds(userB).contains(userA);
    }

    /** 切换拉黑状态，返回操作后的状态。 */
    public Map<String, Object> toggle(Long blockerId, Long targetId) {
        if (targetId == null || targetId < 1) throw new IllegalArgumentException("用户不存在");
        if (blockerId.equals(targetId)) throw new IllegalArgumentException("不能拉黑自己");
        if (userMapper.selectById(targetId) == null) throw new IllegalArgumentException("用户不存在");

        boolean blocked;
        if (blockMapper.exists(blockerId, targetId) > 0) {
            blockMapper.delete(blockerId, targetId);
            blocked = false;
        } else {
            blockMapper.insert(blockerId, targetId);
            blocked = true;
        }
        // 双向缓存都要失效：对方列表里也包含我
        cache.remove(blockerId);
        cache.remove(targetId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocked", blocked);
        return out;
    }

    public List<Map<String, Object>> myBlocks(Long blockerId) {
        List<Map<String, Object>> rows = blockMapper.myBlocks(blockerId);
        return rows == null ? List.of() : rows;
    }
}
