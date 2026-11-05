package com.openminis.rclone.gomobile;

/**
 * [port-upstream-1.16] Log sink for the Go network core's log lines (tsnet +
 * nethub), as the real gomobile binding generates it. The stub version is
 * registered but never called — there is no Go runtime behind it. The real
 * binding's sink receives every Go log line; TailscaleNode.installLogger
 * forwards them into AppLogger and the alert parser.
 */
public interface LogSink {
    void line(String line);
}
