package com.pocketautomator.app;

import com.pocketautomator.app.ITaskListener;

/** The helper Shizuku runs to see which app is in front: see TaskWatcher. */
interface ITaskWatcher {

    /** Shizuku's own call to stop a user service; its number is Shizuku's. */
    void destroy() = 16777114;

    /** Starts reporting to listener, with the tasks as they are right now. */
    void watch(ITaskListener listener) = 1;
}
