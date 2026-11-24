package dev.excalidraw.spenbridge;

oneway interface IPenListener {
    void onButton(boolean down, long kernelTimeUs);
    void onSource(String state, String reason, String node, String name);
}
