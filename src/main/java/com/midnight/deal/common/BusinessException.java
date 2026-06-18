package com.midnight.deal.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    public BusinessException(ErrorCode code){ super(code.name()); this.code = code; }
    public BusinessException(ErrorCode code, String msg){ super(msg); this.code = code; }
}
