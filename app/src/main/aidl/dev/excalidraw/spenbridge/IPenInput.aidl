package dev.excalidraw.spenbridge;

import dev.excalidraw.spenbridge.IPenListener;

interface IPenInput {
    void destroy() = 16777114;
    void start(IPenListener listener, String nodeOverride, int keyCode) = 1;
    void stop() = 2;
    String describeDevices() = 3;
    void learn(IPenListener listener, int timeoutMs) = 4;
    String probeAccess(String node, int timeoutMs) = 5;
    String getProp(String key) = 6;
}
