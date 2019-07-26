package com.stevesoltys.backup;

public interface LiveEventHandler<T> {
    void onEvent(T t);
}
