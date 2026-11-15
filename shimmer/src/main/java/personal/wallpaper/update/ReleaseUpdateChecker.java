package personal.wallpaper.update;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅在用户请求时读取官方公开发布；不下载、不安装 APK，不发送设备数据。 */
public final class ReleaseUpdateChecker {
    public enum State { AVAILABLE, CURRENT, AHEAD, NO_RELEASE, UNKNOWN, UNAVAILABLE, RATE_LIMITED }
    public enum Product {
        MAIN("OS3_Wallpaper_Changer"), MODULE("OS4_E1_Observer");
        public final String repository;
        Product(String repository) { this.repository = repository; }
        public String releasesUrl() { return "https://github.com/GokigenSumire/" + repository + "/releases"; }
    }
    public static final class Result {
        public final State state;
        public final String version, notes, pageUrl;
        public final long versionCode;
        Result(State state, String version, long code, String notes, String url) {
            this.state = state; this.version = version; this.versionCode = code;
            this.notes = notes; this.pageUrl = url;
        }
        public String message() {
            switch (state) {
                case AVAILABLE: return "发现新版本：" + version;
                case CURRENT: return "已是最新公开版本：" + version;
                case AHEAD: return "当前安装版领先于公开发布（" + version + "）";
                case NO_RELEASE: return "暂未找到可访问的正式发布，可前往发布页查看";
                case UNKNOWN: return "找到公开发布，但版本信息不完整，请在发布页核对";
                case RATE_LIMITED: return "检查受限，请稍后重试或打开发布页";
                default: return "检查失败，请检查网络后重试，或打开发布页";
            }
        }
    }
    public interface Transport { Response get(String url) throws IOException; }
    public static final class Response {
        public final int status; public final String body;
        public Response(int status, String body) { this.status = status; this.body = body; }
    }
    private final Transport transport;
    public ReleaseUpdateChecker() { this(ReleaseUpdateChecker::get); }
    public ReleaseUpdateChecker(Transport transport) { this.transport = transport; }
    public Result check(Product product, long installedCode) {
        try {
            Response response = transport.get("https://api.github.com/repos/GokigenSumire/"
                    + product.repository + "/releases/latest");
            return interpret(product, installedCode, response.status, response.body);
        } catch (IOException | RuntimeException unavailable) {
            return empty(product, State.UNAVAILABLE);
        }
    }
    public static Result interpret(Product product, long installedCode, int status, String body) {
        if (status == 404) return empty(product, State.NO_RELEASE);
        if (status == 403 || status == 429) return empty(product, State.RATE_LIMITED);
        if (status != 200) return empty(product, State.UNAVAILABLE);
        try {
            JSONObject release = new JSONObject(body);
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) return empty(product, State.NO_RELEASE);
            String tag = release.getString("tag_name");
            String page = release.getString("html_url");
            // 只允许打开当前项目的官方发布详情；API返回其它网址不作为更新结果。
            if (!page.startsWith(product.releasesUrl() + "/tag/") || tag.length() > 180)
                return empty(product, State.UNKNOWN);
            boolean apk = false;
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                String name = assets.getJSONObject(i).optString("name").toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".apk")) continue;
                boolean compat = name.contains("compat") || name.contains("module");
                if (product == Product.MODULE ? compat : !compat) apk = true;
            }
            long code = versionCode(product, tag);
            if (code <= 0 || !apk) return empty(product, State.UNKNOWN);
            String notes = release.optString("body");
            if (notes.length() > 1800) notes = notes.substring(0, 1800) + "…";
            return new Result(code > installedCode ? State.AVAILABLE : code == installedCode ? State.CURRENT : State.AHEAD,
                    tag, code, notes, page);
        } catch (Exception malformed) { return empty(product, State.UNKNOWN); }
    }
    static long versionCode(Product product, String tag) {
        // 新发布采用 LSPosed 的 VersionCode-VersionName；同时支持已发布的 r105/m97 标签。
        Matcher standard = Pattern.compile("^([1-9][0-9]*)-.+$").matcher(tag);
        Matcher legacy = Pattern.compile(product == Product.MAIN
                ? "^v[0-9]+\\.[0-9]+\\.[0-9]+-p[0-9]+-r([1-9][0-9]*)-m[1-9][0-9]*$"
                : "^v[0-9]+\\.[0-9]+\\.[0-9]+-p[0-9]+-m([1-9][0-9]*)$").matcher(tag);
        try { return standard.matches() ? Long.parseLong(standard.group(1))
                : legacy.matches() ? Long.parseLong(legacy.group(1)) : -1; }
        catch (NumberFormatException overflow) { return -1; }
    }
    private static Result empty(Product product, State state) {
        return new Result(state, "", -1, "", product.releasesUrl());
    }
    private static Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Hyper-Wallpaper-Update");
            int status = connection.getResponseCode();
            if (status != 200) return new Response(status, "");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int read;
                long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime() > deadline
                            || bytes.size() + read > 1024 * 1024) throw new IOException("RELEASE_RESPONSE_LIMIT");
                    bytes.write(buffer, 0, read);
                }
                return new Response(status, bytes.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
