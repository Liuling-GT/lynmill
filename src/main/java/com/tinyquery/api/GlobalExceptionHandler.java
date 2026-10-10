package com.tinyquery.api;

import com.tinyquery.datasource.spec.DataSourceException;
import com.tinyquery.i18n.Messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 * QueryController 内部已有自己的 try-catch，会先拦截它自己的异常
 * 本处理器只覆盖数据源接口和其余漏网异常
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 业务异常：预期内的失败，直接把 message 给前端
    @ExceptionHandler(DataSourceException.class)
    public DataSourceResponse<Void> handleDataSourceException(DataSourceException e) {
        log.warn("数据源操作失败：{}", e.getMessage());
        return DataSourceResponse.failure(e.getMessage());
    }

    // 参数异常：如 data source 类型不合法、字段缺失等
    @ExceptionHandler(IllegalArgumentException.class)
    public DataSourceResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return DataSourceResponse.failure(e.getMessage());
    }

    // 兜底：非预期异常记录堆栈，但不把内部细节暴露给前端
    @ExceptionHandler(Exception.class)
    public DataSourceResponse<Void> handleException(Exception e) {
        log.error("服务内部异常", e);
        return DataSourceResponse.failure(Messages.get("error.internal"));
    }
}