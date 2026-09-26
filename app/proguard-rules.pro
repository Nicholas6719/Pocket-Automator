# Shizuku.newProcess is private in API 13.1.5; Shell.kt reaches it by reflection.
-keep class rikka.shizuku.Shizuku { *; }

# Shizuku starts the task watcher by its class name (see AppWatcher).
-keep class com.pocketautomator.app.TaskWatcher { <init>(); }
