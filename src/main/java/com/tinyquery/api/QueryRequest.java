package com.tinyquery.api;

/**
 * 查询请求
 * page / pageSize 都为 null 时不用分页，一次返回全部命中行
 * （不设上限 —— 大表上可能很大，分页就是为了避免这种情况）
 * 分页参数走 API 而不是写进 SQL，是为了避开 SQLParser 对 LIMIT offset 的切分歧义
 *
 * <p>{@code landscape} / {@code scale} 只对 PDF 导出有意义，其它接口忽略。
 * 放在同一个记录里而不是另建一个导出专用请求，是因为导出接口本来收的就是
 * 「要导哪份数据」，多两个排版选项属于同一件事，拆开反而要前端记两套参数。</p>
 *
 * @param sql       待执行的 SQL
 * @param page      页码，从 1 开始；为 null 表示不分页
 * @param pageSize  每页行数；为 null 时由后端取默认值
 * @param landscape 仅 PDF 导出：是否横向。null 视作 false（纵向）
 * @param scale     仅 PDF 导出：字号缩放，0.5~1.0。null 视作 1.0。
 *                  列多到放不下时缩小字号是最后的办法 —— 比截断成「帖… 发…」强，
 *                  但不如换横向，所以前端应优先推荐横向
 */
public record QueryRequest(String sql, Integer page, Integer pageSize,
                           Boolean landscape, Float scale) {

    /** 是否横向，默认纵向 */
    public boolean isLandscape() {
        return Boolean.TRUE.equals(landscape);
    }

    /** 字号缩放，夹在 0.5~1.0 之间，默认 1.0 */
    public float scaleOrDefault() {
        if (scale == null || scale.isNaN() || scale.isInfinite()) {
            return 1f;
        }
        return Math.max(0.5f, Math.min(1f, scale));
    }
}
