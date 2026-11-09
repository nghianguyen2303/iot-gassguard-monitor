package com.iot.gas.notify;

/** Escape cho parse_mode=HTML của Telegram (chỉ cần &, <, >). */
final class TelegramText {
    private TelegramText() {
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
