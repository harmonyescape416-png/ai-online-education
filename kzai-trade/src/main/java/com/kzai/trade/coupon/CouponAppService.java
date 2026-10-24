package com.kzai.trade.coupon;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kzai.common.exception.BizException;
import com.kzai.common.util.MoneyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CouponAppService {

    private final MktCouponTemplateMapper templateMapper;
    private final MktCouponScopeMapper scopeMapper;
    private final MktCouponUserMapper userMapper;
    private final CouponClock clock;

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        MktCouponTemplate row = new MktCouponTemplate();
        fill(row, body, true);
        LocalDateTime now = clock.now();
        row.setClaimedCount(0);
        row.setUsedCount(0);
        row.setStatus(MktCouponTemplate.DRAFT);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        templateMapper.insert(row);
        replaceScope(row.getId(), courseIds(body));
        return view(templateMapper.selectById(row.getId()));
    }

    @Transactional
    public Map<String, Object> updateDraft(long id, Map<String, Object> body) {
        MktCouponTemplate row = require(id);
        if (!MktCouponTemplate.DRAFT.equals(row.getStatus())) {
            throw new BizException("只有草稿可以编辑");
        }
        fill(row, body, false);
        row.setUpdateTime(clock.now());
        templateMapper.updateById(row);
        replaceScope(id, courseIds(body));
        return view(templateMapper.selectById(id));
    }

    @Transactional
    public Map<String, Object> publish(long id) {
        MktCouponTemplate row = require(id);
        if (!MktCouponTemplate.DRAFT.equals(row.getStatus())) {
            throw new BizException("只有草稿可以发布");
        }
        validateReady(row, scopeIds(id));
        int rows = templateMapper.update(null, Wrappers.<MktCouponTemplate>lambdaUpdate()
                .set(MktCouponTemplate::getStatus, MktCouponTemplate.PUBLISHED)
                .set(MktCouponTemplate::getUpdateTime, clock.now())
                .eq(MktCouponTemplate::getId, id)
                .eq(MktCouponTemplate::getStatus, MktCouponTemplate.DRAFT));
        if (rows != 1) {
            throw new BizException("优惠券状态已变化");
        }
        return view(templateMapper.selectById(id));
    }

    @Transactional
    public Map<String, Object> stop(long id) {
        MktCouponTemplate row = require(id);
        if (!MktCouponTemplate.PUBLISHED.equals(row.getStatus())) {
            throw new BizException("只有发放中的优惠券可以停止");
        }
        templateMapper.update(null, Wrappers.<MktCouponTemplate>lambdaUpdate()
                .set(MktCouponTemplate::getStatus, MktCouponTemplate.STOPPED)
                .set(MktCouponTemplate::getUpdateTime, clock.now())
                .eq(MktCouponTemplate::getId, id)
                .eq(MktCouponTemplate::getStatus, MktCouponTemplate.PUBLISHED));
        return view(templateMapper.selectById(id));
    }

    public List<Map<String, Object>> adminList() {
        return templateMapper.selectList(Wrappers.<MktCouponTemplate>lambdaQuery()
                        .orderByDesc(MktCouponTemplate::getId))
                .stream().map(this::view).toList();
    }

    public List<Map<String, Object>> center(long userId) {
        LocalDateTime now = clock.now();
        return templateMapper.selectList(Wrappers.<MktCouponTemplate>lambdaQuery()
                        .eq(MktCouponTemplate::getStatus, MktCouponTemplate.PUBLISHED)
                        .le(MktCouponTemplate::getClaimStart, now)
                        .gt(MktCouponTemplate::getClaimEnd, now)
                        .orderByDesc(MktCouponTemplate::getId))
                .stream()
                .filter(row -> row.getClaimedCount() < row.getTotalStock())
                .map(row -> {
                    Map<String, Object> item = view(row);
                    item.put("owned", owned(row.getId(), userId));
                    return item;
                }).toList();
    }

    @Transactional
    public Map<String, Object> claim(long userId, long templateId) {
        return issue(userId, require(templateId), MktCouponUser.PUBLIC, null);
    }

    @Transactional
    public Map<String, Object> redeem(long userId, String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("请填写兑换码");
        }
        MktCouponTemplate row = templateMapper.selectOne(Wrappers.<MktCouponTemplate>lambdaQuery()
                .eq(MktCouponTemplate::getRedeemCode, code.trim()));
        if (row == null) {
            throw new BizException("兑换码不存在");
        }
        return issue(userId, row, MktCouponUser.CODE, 1);
    }

    public List<Map<String, Object>> mine(long userId) {
        LocalDateTime now = clock.now();
        return userMapper.selectList(Wrappers.<MktCouponUser>lambdaQuery()
                        .eq(MktCouponUser::getUserId, userId)
                        .orderByDesc(MktCouponUser::getId))
                .stream().map(row -> mineView(row, now)).toList();
    }

    public Map<String, Object> quote(long userId, List<CouponPricing.Line> lines) {
        int total = lines.stream().mapToInt(CouponPricing.Line::price).sum();
        LocalDateTime now = clock.now();
        List<MktCouponUser> coupons = userMapper.selectList(Wrappers.<MktCouponUser>lambdaQuery()
                .eq(MktCouponUser::getUserId, userId));
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> best = null;
        for (MktCouponUser coupon : coupons) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userCouponId", String.valueOf(coupon.getId()));
            item.put("name", coupon.getName());
            item.put("couponType", coupon.getCouponType());
            if (!MktCouponUser.UNUSED.equals(coupon.getStatus())) {
                item.put("usable", false);
                item.put("reason", MktCouponUser.USED.equals(coupon.getStatus()) ? "已使用" : "使用中");
                item.put("discountAmount", 0);
                item.put("payableAmount", total);
                items.add(item);
                continue;
            }
            CouponPricing.Decision decision = CouponPricing.decide(coupon.getCouponType(), coupon.getThresholdAmount(),
                    coupon.getReduceAmount(), coupon.getPayRatio(), coupon.getCourseScope(), coupon.getCourseIds(),
                    coupon.getValidStart(), coupon.getValidEnd(), lines, now);
            item.put("usable", decision.usable());
            item.put("reason", decision.reason());
            item.put("discountAmount", decision.discount());
            item.put("payableAmount", decision.payable());
            item.put("discountYuan", MoneyUtils.centsToYuan(decision.discount()));
            item.put("payableYuan", MoneyUtils.centsToYuan(decision.payable()));
            items.add(item);
            if (decision.usable() && better(item, best, coupon)) {
                best = item;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalAmount", total);
        result.put("totalYuan", MoneyUtils.centsToYuan(total));
        result.put("stacking", false);
        result.put("rule", "每笔订单只能使用一张优惠券");
        result.put("recommendedCouponId", best == null ? null : best.get("userCouponId"));
        result.put("coupons", items);
        return result;
    }

    private boolean better(Map<String, Object> item, Map<String, Object> best, MktCouponUser coupon) {
        if (best == null) {
            return true;
        }
        int discount = (Integer) item.get("discountAmount");
        int bestDiscount = (Integer) best.get("discountAmount");
        if (discount != bestDiscount) {
            return discount > bestDiscount;
        }
        return coupon.getValidEnd().isBefore(validEndOf(best));
    }

    private LocalDateTime validEndOf(Map<String, Object> best) {
        MktCouponUser row = userMapper.selectById(Long.valueOf(String.valueOf(best.get("userCouponId"))));
        return row.getValidEnd();
    }

    private Map<String, Object> issue(long userId, MktCouponTemplate template, String source, Integer codeClaim) {
        LocalDateTime now = clock.now();
        int claimed = templateMapper.claimOne(template.getId(), now);
        if (claimed != 1) {
            throw new BizException(claimFailure(template, now));
        }
        int owned = owned(template.getId(), userId);
        if (owned >= template.getPerUserLimit()) {
            templateMapper.giveBackOne(template.getId(), now);
            throw new BizException("已达到每人限领数量");
        }
        if (codeClaim != null && userMapper.selectCount(Wrappers.<MktCouponUser>lambdaQuery()
                .eq(MktCouponUser::getTemplateId, template.getId())
                .eq(MktCouponUser::getUserId, userId)
                .eq(MktCouponUser::getCodeClaim, 1)) > 0) {
            templateMapper.giveBackOne(template.getId(), now);
            throw new BizException("兑换码已使用");
        }
        MktCouponUser coupon = snapshot(template, userId, owned + 1, source, codeClaim, now);
        try {
            userMapper.insert(coupon);
        } catch (DuplicateKeyException ex) {
            templateMapper.giveBackOne(template.getId(), now);
            throw new BizException(codeClaim != null ? "兑换码已使用" : "已达到每人限领数量");
        }
        return mineView(coupon, now);
    }

    private MktCouponUser snapshot(MktCouponTemplate template, long userId, int slot, String source,
                                   Integer codeClaim, LocalDateTime now) {
        MktCouponUser coupon = new MktCouponUser();
        coupon.setTemplateId(template.getId());
        coupon.setUserId(userId);
        coupon.setSlotNo(slot);
        coupon.setSource(source);
        coupon.setCodeClaim(codeClaim);
        coupon.setName(template.getName());
        coupon.setCouponType(template.getCouponType());
        coupon.setThresholdAmount(template.getThresholdAmount());
        coupon.setReduceAmount(template.getReduceAmount());
        coupon.setPayRatio(template.getPayRatio());
        coupon.setCourseScope(template.getCourseScope());
        coupon.setCourseIds(String.join(",", scopeIds(template.getId()).stream().map(String::valueOf).toList()));
        coupon.setValidStart(template.getValidStart());
        coupon.setValidEnd(template.getValidEnd());
        coupon.setStatus(MktCouponUser.UNUSED);
        coupon.setClaimTime(now);
        coupon.setUpdateTime(now);
        return coupon;
    }

    private String claimFailure(MktCouponTemplate template, LocalDateTime now) {
        MktCouponTemplate current = templateMapper.selectById(template.getId());
        if (current == null || !MktCouponTemplate.PUBLISHED.equals(current.getStatus())) {
            return "优惠券未在发放";
        }
        if (now.isBefore(current.getClaimStart())) {
            return "未到领取时间";
        }
        if (!now.isBefore(current.getClaimEnd())) {
            return "领取已结束";
        }
        return "优惠券已领完";
    }

    private int owned(long templateId, long userId) {
        Long count = userMapper.selectCount(Wrappers.<MktCouponUser>lambdaQuery()
                .eq(MktCouponUser::getTemplateId, templateId)
                .eq(MktCouponUser::getUserId, userId));
        return count == null ? 0 : count.intValue();
    }

    private MktCouponTemplate require(long id) {
        MktCouponTemplate row = templateMapper.selectById(id);
        if (row == null) {
            throw new BizException("优惠券不存在");
        }
        return row;
    }

    private void fill(MktCouponTemplate row, Map<String, Object> body, boolean creating) {
        row.setName(text(body, "name"));
        if (row.getName().isBlank() || row.getName().length() > 80) {
            throw new BizException("优惠券名称需为 1 到 80 字");
        }
        String type = text(body, "couponType");
        if (!MktCouponTemplate.FULL.equals(type) && !MktCouponTemplate.DISCOUNT.equals(type)) {
            throw new BizException("优惠券类型只支持满减或折扣");
        }
        row.setCouponType(type);
        row.setThresholdAmount(nonNegative(body, "thresholdAmount"));
        row.setReduceAmount(MktCouponTemplate.FULL.equals(type) ? positive(body, "reduceAmount") : 0);
        row.setPayRatio(MktCouponTemplate.DISCOUNT.equals(type) ? ratio(body) : 10000);
        row.setTotalStock(positive(body, "totalStock"));
        row.setPerUserLimit(positive(body, "perUserLimit"));
        row.setClaimStart(time(body, "claimStart"));
        row.setClaimEnd(time(body, "claimEnd"));
        row.setValidStart(time(body, "validStart"));
        row.setValidEnd(time(body, "validEnd"));
        if (!row.getClaimStart().isBefore(row.getClaimEnd()) || !row.getValidStart().isBefore(row.getValidEnd())) {
            throw new BizException("时间范围必须开始早于结束");
        }
        String scope = text(body, "courseScope");
        if (!MktCouponTemplate.ALL.equals(scope) && !MktCouponTemplate.INCLUDE.equals(scope)) {
            throw new BizException("适用范围只支持全部课程或指定课程");
        }
        row.setCourseScope(scope);
        String code = body.get("redeemCode") == null ? "" : String.valueOf(body.get("redeemCode")).trim();
        row.setRedeemCode(code.isEmpty() ? null : code);
        if (creating) {
            row.setClaimedCount(0);
            row.setUsedCount(0);
        }
    }

    private void validateReady(MktCouponTemplate row, List<Long> courses) {
        if (MktCouponTemplate.INCLUDE.equals(row.getCourseScope()) && courses.isEmpty()) {
            throw new BizException("指定课程范围至少包含一门课程");
        }
        if (row.getTotalStock() == null || row.getTotalStock() < 1) {
            throw new BizException("发行量至少为 1");
        }
    }

    private void replaceScope(long templateId, List<Long> courseIds) {
        scopeMapper.delete(Wrappers.<MktCouponScope>lambdaQuery().eq(MktCouponScope::getTemplateId, templateId));
        for (Long courseId : courseIds) {
            MktCouponScope scope = new MktCouponScope();
            scope.setTemplateId(templateId);
            scope.setCourseId(courseId);
            scopeMapper.insert(scope);
        }
    }

    private List<Long> scopeIds(long templateId) {
        return scopeMapper.selectList(Wrappers.<MktCouponScope>lambdaQuery()
                        .eq(MktCouponScope::getTemplateId, templateId))
                .stream().map(MktCouponScope::getCourseId).sorted().toList();
    }

    private List<Long> courseIds(Map<String, Object> body) {
        Object raw = body.get("courseIds");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                ids.add(Long.valueOf(String.valueOf(item)));
            }
        }
        return ids.stream().distinct().sorted().toList();
    }

    private Map<String, Object> view(MktCouponTemplate row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(row.getId()));
        view.put("name", row.getName());
        view.put("couponType", row.getCouponType());
        view.put("thresholdAmount", row.getThresholdAmount());
        view.put("reduceAmount", row.getReduceAmount());
        view.put("payRatio", row.getPayRatio());
        view.put("totalStock", row.getTotalStock());
        view.put("claimedCount", row.getClaimedCount());
        view.put("usedCount", row.getUsedCount());
        view.put("remainCount", row.getTotalStock() - row.getClaimedCount());
        view.put("perUserLimit", row.getPerUserLimit());
        view.put("claimStart", row.getClaimStart());
        view.put("claimEnd", row.getClaimEnd());
        view.put("validStart", row.getValidStart());
        view.put("validEnd", row.getValidEnd());
        view.put("courseScope", row.getCourseScope());
        view.put("courseIds", scopeIds(row.getId()).stream().map(String::valueOf).toList());
        view.put("redeemCode", row.getRedeemCode());
        view.put("status", row.getStatus());
        return view;
    }

    private Map<String, Object> mineView(MktCouponUser row, LocalDateTime now) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(row.getId()));
        view.put("templateId", String.valueOf(row.getTemplateId()));
        view.put("name", row.getName());
        view.put("couponType", row.getCouponType());
        view.put("thresholdAmount", row.getThresholdAmount());
        view.put("reduceAmount", row.getReduceAmount());
        view.put("payRatio", row.getPayRatio());
        view.put("validStart", row.getValidStart());
        view.put("validEnd", row.getValidEnd());
        view.put("courseScope", row.getCourseScope());
        view.put("courseIds", splitIds(row.getCourseIds()));
        view.put("status", displayStatus(row, now));
        return view;
    }

    public Map<String, Object> mineOne(long userId, long id) {
        MktCouponUser row = userMapper.selectById(id);
        if (row == null || !Long.valueOf(userId).equals(row.getUserId())) {
            throw new BizException("优惠券不存在");
        }
        return mineView(row, clock.now());
    }

    private static List<String> splitIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }

    static String displayStatus(MktCouponUser row, LocalDateTime now) {
        if (MktCouponUser.USED.equals(row.getStatus())) {
            return "已使用";
        }
        if (MktCouponUser.LOCKED.equals(row.getStatus())) {
            return "使用中";
        }
        if (!now.isBefore(row.getValidEnd())) {
            return "已过期";
        }
        if (now.isBefore(row.getValidStart())) {
            return "未生效";
        }
        return "可使用";
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int nonNegative(Map<String, Object> body, String key) {
        int value = number(body, key);
        if (value < 0) {
            throw new BizException(key + " 不能为负");
        }
        return value;
    }

    private static int positive(Map<String, Object> body, String key) {
        int value = number(body, key);
        if (value < 1) {
            throw new BizException(key + " 至少为 1");
        }
        return value;
    }

    private static int ratio(Map<String, Object> body) {
        int value = number(body, "payRatio");
        if (value < 1 || value > 9999) {
            throw new BizException("折扣支付比例需在 1 到 9999 之间");
        }
        return value;
    }

    private static int number(Map<String, Object> body, String key) {
        try {
            return Integer.parseInt(text(body, key));
        } catch (NumberFormatException ex) {
            throw new BizException(key + " 必须是整数");
        }
    }

    private static LocalDateTime time(Map<String, Object> body, String key) {
        try {
            return LocalDateTime.parse(text(body, key));
        } catch (RuntimeException ex) {
            throw new BizException(key + " 时间格式不正确");
        }
    }
}
