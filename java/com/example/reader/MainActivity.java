package com.example.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 薄原生壳：WebView + JSBridge。界面全在 assets/web/ 里。
 *
 * 三条铁律：
 *   1) AndroidManifest 零权限 —— 导入走 SAF，读写走 App 专属目录。
 *   2) 运行期唯一数据源是 getExternalFilesDir(null)/books/，assets 只在首启播种时被读一次。
 *   3) JSBridge 回调必须切主线程。
 */
public class MainActivity extends Activity {

    private static final String TAG = "ReaderApp";
    private static final String HOST = "appassets.local";
    private static final String ROOT_URL = "https://" + HOST + "/index.html";
    private static final int REQ_IMPORT = 1001;

    private WebView web;
    private File booksDir;

    /** SAF 回调不在原调用栈里，callbackId 必须存成员变量（陷阱 ⑨）。 */
    private String pendingImportCb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        booksDir = new File(getExternalFilesDir(null), "books");
        seedOnce();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage，存字号/夜间等偏好
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebContentsDebuggingEnabled(true);   // 调试命门：chrome://inspect 要靠它
        web.addJavascriptInterface(new Bridge(), "NativeBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return route(req.getUrl());
            }
        });
        // 必须挂 WebChromeClient，否则 window.confirm/alert 被 WebView 静默吞掉、
        // 默认返回 false，删除确认这类 JS 对话框「点了没反应」（陷阱：删除不生效的根因）。
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .show();
                return true;   // 吞掉默认行为，改由我们弹的原生对话框接管
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setCancelable(false)
                        .show();
                return true;
            }
        });

        setContentView(web);
        web.loadUrl(ROOT_URL);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // =====================================================================
    // 1. 首启播种（陷阱 ①：没有 seeded 标记，删掉的种子书下次启动会复活）
    // =====================================================================
    private void seedOnce() {
        final SharedPreferences sp = getSharedPreferences("reader", MODE_PRIVATE);
        // 目录必须先建：File.listFiles() 对「不存在的目录」返回 null，首启必崩（陷阱 ⑤）
        if (!booksDir.exists() && !booksDir.mkdirs()) {
            Log.e(TAG, "cannot create books dir: " + booksDir);
            return;
        }
        if (sp.getBoolean("seeded", false)) return;

        try {
            String[] seeds = getAssets().list("web/seed");   // 空目录/不存在都返回 []，不抛异常
            if (seeds != null) {
                for (String name : seeds) {
                    copyAsset("web/seed/" + name, new File(booksDir, name));
                }
                Log.i(TAG, "seeded " + seeds.length + " books");
            }
        } catch (Exception e) {
            Log.e(TAG, "seed failed", e);
        }
        sp.edit().putBoolean("seeded", true).apply();
    }

    private void copyAsset(String assetPath, File out) throws Exception {
        InputStream in = getAssets().open(assetPath);
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }

    // =====================================================================
    // 2. URL 路由：假域名 + 拦截器，伪装成 https://
    //    没有任何 HTTP 服务器，请求在 shouldInterceptRequest 就被截住。
    // =====================================================================
    private WebResourceResponse route(Uri uri) {
        if (uri == null) return null;
        String host = uri.getHost();
        if (host == null || !HOST.equals(host)) return null;   // 非本域走正常网络

        String path = uri.getPath();
        if (path == null || path.length() == 0 || "/".equals(path)) path = "/index.html";

        // favicon：假域名解析不了，返回空 200 压掉 ERR_NAME_NOT_RESOLVED 噪声（陷阱 ⑥）
        if ("/favicon.ico".equals(path)) {
            return new WebResourceResponse("image/x-icon", "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
        }

        try {
            // 运行期的书：来自 App 专属目录，不是 assets（陷阱：两处都叫 books 是灾难）
            if (path.startsWith("/books/")) {
                String name = safeDecode(path.substring("/books/".length()));
                File f = new File(booksDir, name);
                if (!f.exists() || !f.isFile()) {
                    Log.w(TAG, "book not found: " + name);
                    return new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse("text/plain", "UTF-8",
                        new java.io.FileInputStream(f));
            }

            // 静态资源：assets/web/
            String asset = "web" + path;
            InputStream in = getAssets().open(asset);
            return new WebResourceResponse(mimeOf(path), "UTF-8", in);  // 第二参数必须是 UTF-8
        } catch (Exception e) {
            Log.w(TAG, "route miss: " + path + " -> " + e);
            return null;
        }
    }

    private static String safeDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** MIME 给错，.js 会被当纯文本静默不执行，页面全白（陷阱 ③）。 */
    private static String mimeOf(String path) {
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html";
        if (path.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    // =====================================================================
    // 3. JSBridge：invoke(method, paramsJson, callbackId)
    //    执行在名为 JavaBridge 的线程，不是主线程（陷阱 ④）
    // =====================================================================
    private class Bridge {
        @JavascriptInterface
        public void invoke(String method, String paramsJson, String callbackId) {
            Log.d(TAG, "invoke " + method + " on " + Thread.currentThread().getName());
            String params = (paramsJson == null) ? "" : paramsJson;
            try {
                if ("books.list".equals(method)) {
                    reply(callbackId, listBooks().toString());

                } else if ("books.import".equals(method)) {
                    pendingImportCb = callbackId;                 // 存成员变量（陷阱 ⑨）
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("text/plain");
                            try {
                                startActivityForResult(
                                        Intent.createChooser(i, "选择一个 .txt 文件"), REQ_IMPORT);
                            } catch (Exception e) {
                                Log.e(TAG, "no file picker", e);
                                reply(pendingImportCb, fail("no_picker").toString());
                                pendingImportCb = null;
                            }
                        }
                    });

                } else if ("books.delete".equals(method)) {
                    JSONObject p = new JSONObject(params);
                    reply(callbackId, deleteBook(p.optString("id")).toString());

                } else if ("progress.save".equals(method)) {
                    JSONObject p = new JSONObject(params);
                    saveProgress(p.optString("id"), (float) p.optDouble("pct", 0));
                    reply(callbackId, ok().toString());

                } else if ("progress.load".equals(method)) {
                    JSONObject p = new JSONObject(params);
                    JSONObject r = ok();
                    r.put("pct", loadProgress(p.optString("id")));
                    reply(callbackId, r.toString());

                } else {
                    reply(callbackId, fail("unknown_method").toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "invoke error: " + method, e);
                reply(callbackId, fail("exception").toString());
            }
        }
    }

    /** 回 Web 必须切主线程（陷阱 ④）。 */
    private void reply(final String cbId, final String payload) {
        if (cbId == null || cbId.length() == 0) return;
        runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript(
                        "window.__nativeCallback('" + cbId + "'," + payload + ")", null);
            }
        });
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject fail(String reason) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("reason", reason); } catch (Exception ignored) { }
        return o;
    }

    private JSONObject bookJson(File f) {
        JSONObject o = new JSONObject();
        try {
            String name = f.getName();
            o.put("id", name);
            o.put("title", stripExt(name));
            o.put("url", "/books/" + Uri.encode(name));
            o.put("size", f.length());
            o.put("pct", loadProgress(name));
        } catch (Exception e) {
            Log.e(TAG, "bookJson", e);
        }
        return o;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(0, i) : name;
    }

    // ---------- books.list ----------
    private JSONObject listBooks() {
        JSONObject r = ok();
        try {
            if (!booksDir.exists()) booksDir.mkdirs();
            File[] fs = booksDir.listFiles();      // 目录不存在时返回 null，前面已 mkdirs
            List<File> list = new ArrayList<File>();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".txt")) list.add(f);
                }
            }
            // 同名排序稳定一点
            java.util.Collections.sort(list, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            JSONArray arr = new JSONArray();
            for (File f : list) arr.put(bookJson(f));
            r.put("books", arr);
        } catch (Exception e) {
            Log.e(TAG, "listBooks", e);
        }
        return r;
    }

    // ---------- books.delete ----------
    private JSONObject deleteBook(String id) {
        if (id == null || id.length() == 0) return fail("bad_id");
        File f = new File(booksDir, id);
        boolean okDel = f.exists() && f.delete();
        // 顺带清掉该书的阅读进度
        getSharedPreferences("progress", MODE_PRIVATE).edit().remove(id).apply();
        Log.i(TAG, "delete " + id + " -> " + okDel);
        JSONObject r = okDel ? ok() : fail("delete_failed");
        try { r.put("ok", okDel); } catch (Exception ignored) { }
        return r;
    }

    // ---------- progress ----------
    private void saveProgress(String id, float pct) {
        if (id == null) return;
        if (pct < 0) pct = 0;
        if (pct > 1) pct = 1;
        getSharedPreferences("progress", MODE_PRIVATE).edit().putFloat(id, pct).apply();
    }

    private double loadProgress(String id) {
        if (id == null) return 0;
        double p = getSharedPreferences("progress", MODE_PRIVATE).getFloat(id, 0f);
        return p;
    }

    // =====================================================================
    // 4. SAF 导入：文件内容全程不经过 Web 层（Uri -> InputStream -> 文件）
    // =====================================================================
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT) return;

        final String cbId = pendingImportCb;
        pendingImportCb = null;

        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (cbId != null) reply(cbId, fail("canceled").toString());
            return;
        }

        final Uri uri = data.getData();
        // 选文件期间 Activity 可能被回收，回调丢了就只刷书架
        final boolean hasCb = cbId != null;

        new Thread(new Runnable() {
            public void run() {
                JSONObject result = doImport(uri);
                if (hasCb) reply(cbId, result.toString());
                else Log.w(TAG, "import finished but callback gone");
            }
        }).start();
    }

    private JSONObject doImport(Uri uri) {
        try {
            String display = displayName(uri);
            byte[] raw = readAll(getContentResolver().openInputStream(uri));
            if (raw == null || raw.length == 0) return fail("empty_file");

            // 编码探测（陷阱 ⑦）：中文 txt 大概率 GBK，按 UTF-8 读会全屏乱码
            String text = decodeSmart(raw);
            // 去 BOM（陷阱 ⑪）
            if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);

            File out = dedupe(display);
            FileOutputStream os = new FileOutputStream(out);
            try {
                os.write(text.getBytes("UTF-8"));   // 统一转 UTF-8 落盘
            } finally {
                os.close();
            }
            Log.i(TAG, "imported " + out.getName() + " (" + raw.length + " bytes)");

            JSONObject r = ok();
            r.put("book", bookJson(out));
            return r;
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            return fail("io_error");
        }
    }

    /** 同名消歧：lunyu.txt 已存在 -> lunyu(1).txt（陷阱 ⑤） */
    private File dedupe(String name) {
        if (name == null || name.length() == 0) name = "untitled.txt";
        name = name.replace('/', '_').replace('\\', '_');
        File f = new File(booksDir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) { base = name.substring(0, i); ext = name.substring(i); }
        for (int n = 1; n < 1000; n++) {
            f = new File(booksDir, base + "(" + n + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(booksDir, base + "_" + System.currentTimeMillis() + ext);
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && n.length() > 0) return n;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query display name failed", e);
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return (last == null) ? "untitled.txt" : last;
    }

    private static byte[] readAll(InputStream in) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "readAll", e);
            return null;
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 严格模式试 UTF-8，抛异常就按 GB18030（GBK 超集）解码。
     * 结果统一是 Java String，落盘时再统一写成 UTF-8。
     */
    private static String decodeSmart(byte[] data) {
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder();
            dec.onMalformedInput(CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = dec.decode(ByteBuffer.wrap(data));
            return cb.toString();
        } catch (Exception notUtf8) {
            try {
                return new String(data, "GB18030");
            } catch (Exception e) {
                return new String(data, Charset.defaultCharset());
            }
        }
    }
}
