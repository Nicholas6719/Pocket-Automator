#!/system/bin/sh
# Pocket Automator's keep-alive watchdog. Runs as root through Retroid's own
# root service, started by Pocket Automator after each boot.
#
# Every 20 seconds it checks, and starts again if it isn't running:
#   - Shizuku (its server, started the way Shizuku's own starter does it)
#   - Pocket Automator's service
#   - each app in $DIR/keepalive: "package [service component]"; with a
#     component that service is started, otherwise the app gets the
#     start-at-boot broadcast it already listens for.
# It stops when $DIR/enabled is removed (Pocket Automator's Keep alive switch)
# or when Pocket Automator is uninstalled.

DIR=/data/local/tmp/pocket-automator
SELF=com.pocketautomator.app
SHIZUKU=moe.shizuku.privileged.api

echo $$ > "$DIR/watchdog.pid"

shizuku_lib() {
    base=$(pm path "$SHIZUKU" 2>/dev/null | head -n 1 | sed 's/^package://; s/base\.apk$//')
    [ -n "$base" ] && echo "${base}lib/arm64/libshizuku.so"
}

service_running() {
    dumpsys activity services "$1" 2>/dev/null | grep -q "ServiceRecord"
}

# Runs while switched on, and only while Pocket Automator is installed.
while [ -f "$DIR/enabled" ] && ls -d /data/app/*/"$SELF"-* >/dev/null 2>&1; do
    if ! pidof shizuku_server >/dev/null 2>&1; then
        lib=$(shizuku_lib)
        [ -n "$lib" ] && [ -f "$lib" ] && "$lib" >/dev/null 2>&1 && echo "$(date '+%m-%d %H:%M:%S') started Shizuku" >> "$DIR/watchdog.log"
    fi
    # The service, not just the process: other apps can wake the process on its own.
    if ! service_running "$SELF/.AutomatorService"; then
        am start-foreground-service -n "$SELF/.AutomatorService" >/dev/null 2>&1 && echo "$(date '+%m-%d %H:%M:%S') started Pocket Automator" >> "$DIR/watchdog.log"
    fi
    if [ -f "$DIR/keepalive" ]; then
        while read -r pkg component; do
            [ -z "$pkg" ] && continue
            if [ -n "$component" ]; then
                service_running "$component" && continue
            else
                pidof "$pkg" >/dev/null 2>&1 && continue
            fi
            if [ -n "$component" ]; then
                am start-foreground-service -n "$component" >/dev/null 2>&1
            else
                am broadcast -a android.intent.action.BOOT_COMPLETED -p "$pkg" >/dev/null 2>&1
            fi
            echo "$(date '+%m-%d %H:%M:%S') started $pkg" >> "$DIR/watchdog.log"
        done < "$DIR/keepalive"
    fi
    # Keep the log short.
    [ -f "$DIR/watchdog.log" ] && [ "$(wc -l < "$DIR/watchdog.log")" -gt 200 ] && tail -n 100 "$DIR/watchdog.log" > "$DIR/watchdog.log.tmp" && mv "$DIR/watchdog.log.tmp" "$DIR/watchdog.log"
    sleep 20
done
rm -f "$DIR/watchdog.pid"
