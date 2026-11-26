package io.legacy.localsend.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LocalSend protocol constants. Values verified against LocalSend v1.15.1
 * (common/lib/src/constants.dart and app/lib/util/api_route_builder.dart).
 */
public final class Protocol {
    public static final String VERSION = "2.0";
    public static final String PEER_VERSION = "1.0";
    public static final int DEFAULT_PORT = 53317;
    public static final String MULTICAST_GROUP = "224.0.0.167";

    private static final String BASE = "/api/localsend";

    public static final String INFO_V1 = BASE + "/v1/info";
    public static final String INFO_V2 = BASE + "/v2/info";
    public static final String REGISTER_V1 = BASE + "/v1/register";
    public static final String REGISTER_V2 = BASE + "/v2/register";
    public static final String PREPARE_UPLOAD_V1 = BASE + "/v1/send-request";
    public static final String PREPARE_UPLOAD_V2 = BASE + "/v2/prepare-upload";
    public static final String UPLOAD_V1 = BASE + "/v1/send";
    public static final String UPLOAD_V2 = BASE + "/v2/upload";
    public static final String CANCEL_V1 = BASE + "/v1/cancel";
    public static final String CANCEL_V2 = BASE + "/v2/cancel";
    public static final String SHOW_V1 = BASE + "/v1/show";
    public static final String SHOW_V2 = BASE + "/v2/show";

    public static String infoPath(String version) {
        return "1.0".equals(version) ? INFO_V1 : INFO_V2;
    }

    public static String registerPath(String version) {
        return "1.0".equals(version) ? REGISTER_V1 : REGISTER_V2;
    }

    public static String prepareUploadPath(String version) {
        return "1.0".equals(version) ? PREPARE_UPLOAD_V1 : PREPARE_UPLOAD_V2;
    }

    public static String uploadPath(String version) {
        return "1.0".equals(version) ? UPLOAD_V1 : UPLOAD_V2;
    }

    public static String cancelPath(String version) {
        return "1.0".equals(version) ? CANCEL_V1 : CANCEL_V2;
    }

    private static final Map<String, String> MIME = new HashMap<String, String>();

    static {
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("png", "image/png");
        MIME.put("gif", "image/gif");
        MIME.put("bmp", "image/bmp");
        MIME.put("webp", "image/webp");
        MIME.put("mp4", "video/mp4");
        MIME.put("3gp", "video/3gpp");
        MIME.put("mkv", "video/x-matroska");
        MIME.put("avi", "video/x-msvideo");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("wav", "audio/wav");
        MIME.put("pdf", "application/pdf");
        MIME.put("txt", "text/plain");
        MIME.put("log", "text/plain");
        MIME.put("md", "text/markdown");
        MIME.put("json", "application/json");
        MIME.put("xml", "application/xml");
        MIME.put("html", "text/html");
        MIME.put("htm", "text/html");
        MIME.put("apk", "application/vnd.android.package-archive");
        MIME.put("zip", "application/zip");
        MIME.put("rar", "application/vnd.rar");
        MIME.put("7z", "application/x-7z-compressed");
        MIME.put("doc", "application/msword");
        MIME.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME.put("xls", "application/vnd.ms-excel");
        MIME.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME.put("ppt", "application/vnd.ms-powerpoint");
        MIME.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    private Protocol() {
    }

    public static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static String mimeFor(String fileName) {
        String mime = MIME.get(extension(fileName));
        return mime != null ? mime : "application/octet-stream";
    }

    /** v1 receivers expect the legacy enum name instead of a mime type. */
    public static String legacyTypeForName(String fileName) {
        return legacyTypeForMime(mimeFor(fileName));
    }

    public static String legacyTypeForMime(String mime) {
        if (mime == null) return "other";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("text/")) return "text";
        if ("application/pdf".equals(mime)) return "pdf";
        if ("application/vnd.android.package-archive".equals(mime)) return "apk";
        return "other";
    }

    public static String legacyTypeForRaw(String raw) {
        if (raw == null) return "other";
        if (raw.indexOf('/') >= 0) return legacyTypeForMime(raw);
        return raw;
    }

    public static boolean isTextType(String raw) {
        if (raw == null) return false;
        return "text".equals(raw) || raw.startsWith("text/");
    }

    public static java.io.File defaultDownloadDir() {
        return Paths.downloadDir();
    }
}

