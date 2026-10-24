package com.kzai.trade.testsupport;

/** M15.20-C 测试辅助：捕获 mapper.updateById 最后保存实体（mock 无状态） */
public class CompensationTestSupport {
    public static EnrollCompensationHolder holder = new EnrollCompensationHolder();

    public static class EnrollCompensationHolder {
        public volatile Object lastSaved;
    }

    public static EnrollCompensationEntityLike get() { return null; }

    public static class EnrollCompensationEntityLike {
    }
}
