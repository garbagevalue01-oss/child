package com.monitoring.child;

public class UrlHelper {
    public static String getApiUrl(String serverIp, String path) {
        String cleanIp = serverIp.trim();
        if (cleanIp.startsWith("http://") || cleanIp.startsWith("https://") || cleanIp.startsWith("ws://") || cleanIp.startsWith("wss://")) {
            cleanIp = cleanIp.substring(cleanIp.indexOf("://") + 3);
        }
        
        // Use secure HTTPS if the input specified https://, or if it is a Render domain, or if it looks like a domain name without a port
        boolean isSecure = serverIp.startsWith("https://") 
                || cleanIp.contains("render.com") 
                || (!cleanIp.contains(":") && !cleanIp.matches("^[0-9\\.]+$"));
                
        String protocol = isSecure ? "https://" : "http://";
        return protocol + cleanIp + path;
    }

    public static String getWsUrl(String serverIp, String path) {
        String cleanIp = serverIp.trim();
        if (cleanIp.startsWith("http://") || cleanIp.startsWith("https://") || cleanIp.startsWith("ws://") || cleanIp.startsWith("wss://")) {
            cleanIp = cleanIp.substring(cleanIp.indexOf("://") + 3);
        }
        
        boolean isSecure = serverIp.startsWith("https://") 
                || serverIp.startsWith("wss://") 
                || cleanIp.contains("render.com") 
                || (!cleanIp.contains(":") && !cleanIp.matches("^[0-9\\.]+$"));
                
        String protocol = isSecure ? "wss://" : "ws://";
        return protocol + cleanIp + path;
    }
}
