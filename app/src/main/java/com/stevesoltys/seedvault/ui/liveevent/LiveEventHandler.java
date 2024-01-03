package com.stevesoltys.seedvault.ui.liveevent;

public interface LiveEventHandler<T> {
    void onEvent(T t);
}
