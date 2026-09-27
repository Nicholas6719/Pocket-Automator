package com.pocketautomator.app;

/** The helper Shizuku keeps running to start Pocket Automator again: see Guard. */
interface IGuard {

    /** Shizuku's own call to stop a user service; its number is Shizuku's. */
    void destroy() = 16777114;

    /** Starts the service [component] ("package/class") again whenever its app isn't running. */
    void keep(String component) = 1;
}
