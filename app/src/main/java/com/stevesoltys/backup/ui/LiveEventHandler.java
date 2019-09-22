package com.stevesoltys.backup.ui;

public interface LiveEventHandler<T> {
    void onEvent(T t);
}
