package com.stevesoltys.seedvault.ui;

public interface LiveEventHandler<T> {
    void onEvent(T t);
}
