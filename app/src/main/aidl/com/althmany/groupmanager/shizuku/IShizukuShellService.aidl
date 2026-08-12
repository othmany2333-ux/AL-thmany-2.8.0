package com.althmany.groupmanager.shizuku;

interface IShizukuShellService {
    String execute(String command, int timeoutMs);
    int serviceUid();
    String fastSnapshot(String targetPackage, int maxNodes);
    boolean fastTap(int x, int y);
    boolean fastClickNode(String targetPackage, int x, int y);
    boolean fastSwipe(int startX, int startY, int endX, int endY, int durationMs);
    boolean fastBack();
    String fastFindWidePositiveAction();
    long fastEventSequence(String targetPackage);
    long waitForFastEvent(String targetPackage, long afterSequence, int timeoutMs);
    String waitAndSnapshot(String targetPackage, long afterSequence, int timeoutMs, int maxNodes);
    String fastUiStatus();
    boolean fastResetUiAutomation();
}
