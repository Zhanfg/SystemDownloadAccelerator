package de.robv.android.xposed;

import java.lang.reflect.Member;

public final class XposedBridge {
    private XposedBridge() {}
    public static void hookMethod(Member hookMethod, XC_MethodHook callback) {}
    public static void log(String text) {}
    public static void log(Throwable throwable) {}
}
