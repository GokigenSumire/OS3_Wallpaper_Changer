package personal.wallpaper.update;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.IOException;
import static org.junit.Assert.*;
import static personal.wallpaper.update.ReleaseUpdateChecker.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ReleaseUpdateCheckerTest {
    private String release(Product product, String tag, String asset) throws Exception {
        return new JSONObject().put("draft", false).put("prerelease", false).put("tag_name", tag)
                .put("html_url", product.releasesUrl() + "/tag/" + tag).put("body", "发布说明")
                .put("assets", new JSONArray().put(new JSONObject().put("name", asset))).toString();
    }
    @Test public void standardTagsCompareVersionCodeForBothProducts() throws Exception {
        for (Product product : Product.values()) {
            String apk = product == Product.MAIN ? "wallpaper-main115.apk" : "compat-module115.apk";
            assertEquals(State.AVAILABLE, interpret(product, 114, 200, release(product,"115-1.4.8",apk)).state);
            assertEquals(State.CURRENT, interpret(product, 115, 200, release(product,"115-1.4.8",apk)).state);
            assertEquals(State.AHEAD, interpret(product, 116, 200, release(product,"115-1.4.8",apk)).state);
        }
    }
    @Test public void publishedLegacyMainUsesMainCodeNotModuleCode() throws Exception {
        Result result = interpret(Product.MAIN, 105, 200, release(Product.MAIN,
                "v1.3.7-p11-r105-m97", "Hyper-Wallpaper-1.3.7-main105.apk"));
        assertEquals(105, result.versionCode); assertEquals(State.CURRENT,result.state);
    }
    @Test public void publishedLegacyModuleUsesModuleCode() throws Exception {
        Result result = interpret(Product.MODULE, 99, 200, release(Product.MODULE,
                "v1.1.22-p10-m97", "Hyper-Wallpaper-Compat-1.1.22-v97.apk"));
        assertEquals(97, result.versionCode); assertEquals(State.AHEAD,result.state);
        assertTrue(result.message().contains("领先"));
    }
    @Test public void moduleAssetCannotAdvertiseMainUpdate() throws Exception {
        assertEquals(State.UNKNOWN,interpret(Product.MAIN,1,200,release(Product.MAIN,"115-test","compat-module99.apk")).state);
    }
    @Test public void mainAssetCannotAdvertiseModuleUpdate() throws Exception {
        assertEquals(State.UNKNOWN,interpret(Product.MODULE,1,200,release(Product.MODULE,"99-test","wallpaper-main115.apk")).state);
    }
    @Test public void sourceArchiveIsNotAnInstallableUpdate() throws Exception {
        assertEquals(State.UNKNOWN,interpret(Product.MAIN,1,200,release(Product.MAIN,"115-test","main115.zip")).state);
    }
    @Test public void unknownSemverDoesNotPretendCurrent() throws Exception {
        assertEquals(State.UNKNOWN,interpret(Product.MAIN,1,200,release(Product.MAIN,"v9.0.0","main.apk")).state);
    }
    @Test public void nonpositiveAndOverflowCodesStayUnknown() {
        for (String tag : new String[]{"0-version", "-1-version", "9999999999999999999999999-version", "115-"})
            assertEquals(-1,versionCode(Product.MAIN,tag));
    }
    @Test public void legacyTagsAreProductSpecific() {
        assertEquals(-1,versionCode(Product.MAIN,"v1.1.22-p10-m97"));
        assertEquals(-1,versionCode(Product.MODULE,"v1.3.7-p11-r105-m97"));
    }
    @Test public void draftOrPrereleaseNeverOffered() throws Exception {
        JSONObject release = new JSONObject(release(Product.MAIN,"115-test","main.apk"));
        assertEquals(State.NO_RELEASE,interpret(Product.MAIN,1,200,release.put("draft",true).toString()).state);
        assertEquals(State.NO_RELEASE,interpret(Product.MAIN,1,200,release.put("draft",false).put("prerelease",true).toString()).state);
    }
    @Test public void foreignOrLookalikePageIsRejected() throws Exception {
        JSONObject release = new JSONObject(release(Product.MAIN,"115-test","main.apk"));
        for (String url : new String[]{"https://example.com/app.apk",Product.MODULE.releasesUrl()+"/tag/115-test",
                Product.MAIN.releasesUrl()+".evil.test/tag/x"}) {
            assertEquals(State.UNKNOWN,interpret(Product.MAIN,1,200,release.put("html_url",url).toString()).state);
        }
    }
    @Test public void malformedOrIncompleteResponseIsUnknown() {
        for (String body : new String[]{"", "[]", "{}", "{broken"})
            assertEquals(State.UNKNOWN,interpret(Product.MAIN,1,200,body).state);
    }
    @Test public void networkAndServerFailuresNeverReportLatest() {
        for (int status : new int[]{301,401,500,503}) {
            Result result=interpret(Product.MAIN,115,status,"");
            assertEquals(State.UNAVAILABLE,result.state); assertFalse(result.message().contains("已是最新"));
            assertEquals(Product.MAIN.releasesUrl(),result.pageUrl);
        }
        assertEquals(State.NO_RELEASE,interpret(Product.MAIN,1,404,"").state);
        assertEquals(State.RATE_LIMITED,interpret(Product.MAIN,1,403,"").state);
        assertEquals(State.RATE_LIMITED,interpret(Product.MODULE,1,429,"").state);
    }
    @Test public void transportExceptionProducesRetryableFailure() {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> { throw new IOException("offline"); });
        assertEquals(State.UNAVAILABLE,checker.check(Product.MODULE,99).state);
    }
    @Test public void onlyExplicitCheckContactsCorrectPublicEndpointWithoutDeviceData() {
        int[] calls = {0};
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(url -> {
            calls[0]++;
            assertEquals("https://api.github.com/repos/GokigenSumire/OS4_E1_Observer/releases/latest",url);
            return new Response(404,"");
        });
        assertEquals(0,calls[0]); checker.check(Product.MODULE,99); assertEquals(1,calls[0]);
    }
    @Test public void notesAreBoundedAndPageRemainsOfficial() throws Exception {
        JSONObject release = new JSONObject(release(Product.MAIN,"115-test","main.apk"));
        Result result=interpret(Product.MAIN,114,200,release.put("body","x".repeat(4000)).toString());
        assertEquals(1801,result.notes.length()); assertTrue(result.pageUrl.startsWith(Product.MAIN.releasesUrl()));
    }
}
