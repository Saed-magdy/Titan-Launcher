package com.qinggan.os;

import android.os.Bundle;

public abstract class UEventObserver {
    public UEventObserver() {}
    public UEventObserver(char separator) {}
    public UEventObserver(int group) {}
    public UEventObserver(int group, char separator) {}

    public final synchronized void startObserving(String match) {}
    public final synchronized void stopObserving() {}

    public abstract void onUEvent(int len, Bundle data);
    public void onUEvent(String message) {}
}
