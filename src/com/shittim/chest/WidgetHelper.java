package com.shittim.chest;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面小组件公共逻辑。
 *
 * v11：
 *   1) 地图小组件自带学生 —— 内置 {@link WidgetSeed}（由 data.js + masks.js 离线生成，
 *      含每张地图的学生头像 id / 归一化坐标 / 在线状态）。应用从未打开过、被回收、
 *      或桌面切换地图时，直接用种子点位绘制真实圆形头像，不再依赖 WebView 推送。
 *      JS 推送的 pins 优先（第 4 项为头像 id），推送到位后覆盖种子。
 *   2) 阿洛娜 / 终端支持查看历史对话 —— aronaLog / termLog 持久化在 SharedPreferences，
 *      每条对话/输出追加写入，小组件上按页显示，可用「更早 / 更新 / 最新」翻页。
 *   3) 保留 v10 的打字输入弹窗、桌面快捷按钮与双向自由缩放。
 */
public final class WidgetHelper {

    public static final String ACTION_WIDGET = "com.shittim.chest.WIDGET_ACTION";
    static final String PREFS = "shittim_widget";
    private static final String KEY_JSON = "data";
    static final String KEY_QUEUE = "actions";

    private static final String DEFAULT_MAP_KEY = "Abydos";
    private static final int BMP_W = 320;
    private static final int BMP_H = 203;
    private static final int ICON = 22;          /* 头像直径（位图像素），比 v10 的圆点大 2.4 倍 */
    private static final int PAGE = 4;           /* 小组件每页显示的历史条数 */
    private static final int LOG_MAX = 100;      /* 历史日志上限 */

    private static final String[] MAP_SHORT   = { "‹", "›", "更新", "名录" };
    private static final String[] MAP_LONG    = { "‹ 上一张", "下一张 ›", "更新位置", "学生名录" };
    private static final String[] MAP_ACT     = { "prev", "next", "refresh", "list" };

    private static final String[] ARONA_SHORT = { "说一句", "提问" };
    private static final String[] ARONA_LONG  = { "说一句", "问阿洛娜" };
    private static final String[] ARONA_ACT   = { "chat", "ask" };

    private static final String[] TERM_SHORT  = { "ls", "map", "名单", "help" };
    private static final String[] TERM_LONG   = { "ls -l", "当前地图", "学生名单", "help" };
    private static final String[] TERM_ACT    = { "exec:ls", "exec:map", "exec:students", "exec:help" };

    private static final String[] FALLBACK_LINES = {
        "老师，今天也辛苦啦~ 要记得休息一下哦。",
        "什亭之匣的地图已经准备好了，随时可以出发。",
        "有什么想问的，都可以告诉阿洛娜哦~",
        "基沃托斯今天也很热闹呢，老师。",
        "检测到新的学生信号，要不要看看地图？",
        "老师别太勉强自己，我会一直在这里的。"
    };

    private WidgetHelper() { }

    /* ==================== 状态存取（合并式） ==================== */

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject data(Context c) {
        try { return new JSONObject(prefs(c).getString(KEY_JSON, "")); }
        catch (Exception e) { return new JSONObject(); }
    }

    static void writeData(Context c, JSONObject o) {
        try { prefs(c).edit().putString(KEY_JSON, o.toString()).commit(); }
        catch (Exception ignored) { }
    }

    /** 追加一条（或一批）历史记录；与最近 12 条去重，避免桌面输入与应用回推重复。 */
    private static void appendLog(JSONObject d, String key, JSONArray adds) {
        if (adds == null || adds.length() == 0) return;
        JSONArray log = d.optJSONArray(key);
        if (log == null) log = new JSONArray();
        for (int i = 0; i < adds.length(); i++) {
            Object o = adds.opt(i);
            if (o == null) continue;
            String s = (o instanceof JSONObject) ? o.toString() : String.valueOf(o);
            if (s.length() == 0) continue;
            boolean dup = false;
            /* 全量去重：应用启动时会把本地历史整批回推，需避免重复堆叠 */
            for (int j = 0; j < log.length(); j++) {
                if (s.equals(log.optString(j, ""))) { dup = true; break; }
            }
            if (dup) continue;
            log.put(s);
        }
        if (log.length() > LOG_MAX) {
            JSONArray n = new JSONArray();
            for (int i = log.length() - LOG_MAX; i < log.length(); i++) n.put(log.opt(i));
            log = n;
        }
        try { d.put(key, log); } catch (Exception ignored) { }
    }

    private static JSONArray wrap(Object o) {
        JSONArray a = new JSONArray();
        if (o != null) a.put(o);
        return a;
    }

    /**
     * JS 桥写入状态。JS 每次只推送变化的字段，因此这里做「字段合并」而不是整体覆盖。
     * aronaAppend / termAppend 为追加语义（单条或数组），aronaClear / termClear 清空日志。
     */
    public static void save(Context c, String json) {
        if (c == null || json == null) return;
        JSONObject merged = data(c);
        try {
            JSONObject in = new JSONObject(json);
            java.util.Iterator<String> it = in.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.equals("aronaAppend") || k.equals("termAppend")
                        || k.equals("aronaClear") || k.equals("termClear")) continue;
                merged.put(k, in.get(k));
            }
            if (in.has("aronaAppend")) {
                Object o = in.get("aronaAppend");
                appendLog(merged, "aronaLog", o instanceof JSONArray ? (JSONArray) o : wrap(o));
            }
            if (in.has("termAppend")) {
                Object o = in.get("termAppend");
                appendLog(merged, "termLog", o instanceof JSONArray ? (JSONArray) o : wrap(o));
            }
            if (in.has("aronaClear")) { merged.remove("aronaLog"); merged.remove("aronaPage"); }
            if (in.has("termClear"))  { merged.remove("termLog");  merged.remove("termPage");  }
        } catch (Exception ignored) { }
        try { merged.remove("status"); } catch (Exception ignored) { }
        syncMapIndex(merged);
        writeData(c, merged);
        updateAll(c);
    }

    private static void syncMapIndex(JSONObject d) {
        try {
            JSONArray maps = d.optJSONArray("maps");
            String key = d.optString("mapKey", "");
            if (maps == null || key.length() == 0) return;
            for (int i = 0; i < maps.length(); i++) {
                JSONObject m = maps.optJSONObject(i);
                if (m != null && key.equals(m.optString("key", ""))) {
                    d.put("mapIndex", i);
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    /* ==================== 动作队列 ==================== */

    static JSONArray queue(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY_QUEUE, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static void clearQueue(Context c) {
        try { prefs(c).edit().remove(KEY_QUEUE).commit(); }
        catch (Exception ignored) { }
    }

    private static void enqueue(Context c, String kind, String act) {
        try {
            JSONArray q = queue(c);
            JSONObject o = new JSONObject();
            o.put("k", kind);
            o.put("a", act);
            q.put(o);
            if (q.length() > 40) {
                JSONArray n = new JSONArray();
                for (int i = q.length() - 40; i < q.length(); i++) n.put(q.opt(i));
                q = n;
            }
            prefs(c).edit().putString(KEY_QUEUE, q.toString()).commit();
        } catch (Exception ignored) { }
    }

    /* ==================== 桌面文字输入 ==================== */

    public static void submitInput(Context c, String kind, String text) {
        if (c == null || text == null) return;
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() == 0) return;
        if (t.length() > 500) t = t.substring(0, 500);
        boolean arona = !"term".equals(kind);

        JSONObject d = data(c);
        try {
            if (arona) {
                d.put("line", "你：" + t);
                appendLog(d, "aronaLog",
                        wrap(new JSONObject().put("r", "u").put("t", t).toString()));
                d.put("status", "已发送 · 阿洛娜的回复会自动回到桌面");
            } else {
                d.put("term", "$ " + t);
                JSONArray tail = d.optJSONArray("termTail");
                if (tail == null) tail = new JSONArray();
                tail.put("$ " + t);
                JSONArray n = new JSONArray();
                for (int i = Math.max(0, tail.length() - 6); i < tail.length(); i++) n.put(tail.opt(i));
                d.put("termTail", n);
                appendLog(d, "termLog", wrap("$ " + t));
                d.put("status", "已提交 · 命令输出会自动回到桌面");
            }
            d.remove("aronaPage");
            d.remove("termPage");
        } catch (Exception ignored) { }
        writeData(c, d);

        String act = arona ? "say:" + t : "exec:" + t;
        boolean sent = MainActivity.dispatchWidgetAction(kind, act);
        if (!sent) enqueue(c, kind, act);
        updateAll(c);
    }

    /* ==================== 桌面按钮点击 ==================== */

    public static void handleAction(Context c, Intent i) {
        if (c == null || i == null) return;
        String kind = i.getStringExtra("kind");
        String act = i.getStringExtra("wg_action");
        if (kind == null || act == null) return;

        JSONObject d = data(c);

        /* 历史翻页：纯本地状态变更，不派发给 WebView */
        if (act.equals("hist_prev") || act.equals("hist_next") || act.equals("hist_latest")) {
            String pk = "arona".equals(kind) ? "aronaPage" : "termPage";
            String lk = "arona".equals(kind) ? "aronaLog" : "termLog";
            JSONArray log = d.optJSONArray(lk);
            int n = log == null ? 0 : log.length();
            int pages = Math.max(1, (n + PAGE - 1) / PAGE);
            int p = d.optInt(pk, 0);
            if (act.equals("hist_latest")) p = 0;
            else if (act.equals("hist_prev")) p = Math.min(pages - 1, p + 1);
            else p = Math.max(0, p - 1);
            try { d.put(pk, p); } catch (Exception ignored) { }
            writeData(c, d);
            updateAll(c);
            return;
        }

        String outAct = act;
        try {
            if ("map".equals(kind)) {
                if ("prev".equals(act) || "next".equals(act)) {
                    JSONArray maps = d.optJSONArray("maps");
                    int n = maps == null ? 0 : maps.length();
                    int idx = d.optInt("mapIndex", 0);
                    if (n > 0) {
                        int t = "next".equals(act) ? (idx + 1) % n : (idx - 1 + n) % n;
                        JSONObject m = maps.optJSONObject(t);
                        if (m != null) {
                            d.put("map", m.optString("name", d.optString("map", "")));
                            d.put("mapIndex", t);
                            d.put("mapKey", m.optString("key", DEFAULT_MAP_KEY));
                        }
                        outAct = "goto:" + t;
                    }
                    /* 关键：不再把 pins 置空。清空后若应用未运行，桌面会永久没有学生。
                       改为删除该字段，让 build() 立即用内置种子点位绘制新地图的学生。 */
                    d.remove("pins");
                    d.put("status", "桌面已切换地图 · 学生位置已同步");
                } else if ("refresh".equals(act)) {
                    d.put("status", "已更新学生位置 · 应用内同步");
                } else if ("list".equals(act)) {
                    d.put("status", "已打开学生名录");
                }
            } else if ("arona".equals(kind)) {
                if ("chat".equals(act)) {
                    JSONArray lines = d.optJSONArray("lines");
                    int n = lines == null ? FALLBACK_LINES.length : lines.length();
                    if (n <= 0) n = FALLBACK_LINES.length;
                    int idx = d.optInt("lineIndex", -1);
                    int t = (idx + 1 + n) % n;
                    String s = lines == null ? FALLBACK_LINES[t % FALLBACK_LINES.length]
                                             : lines.optString(t, "");
                    if (s == null || s.length() == 0) s = FALLBACK_LINES[t % FALLBACK_LINES.length];
                    d.put("lineIndex", t);
                    d.put("line", s);
                    appendLog(d, "aronaLog",
                            wrap(new JSONObject().put("r", "a").put("t", s).toString()));
                    d.remove("aronaPage");
                    outAct = "chat:" + t;
                } else if ("ask".equals(act)) {
                    d.put("line", "正在询问阿洛娜…");
                    d.put("status", "等待阿洛娜回应 · 结果会自动回到桌面");
                }
            } else if ("term".equals(kind)) {
                if (act.startsWith("exec:")) {
                    String cmd = act.substring(5);
                    d.put("term", "$ " + cmd);
                    appendLog(d, "termLog", wrap("$ " + cmd));
                    d.remove("termPage");
                    d.put("status", "执行中… · 输出会自动回到桌面");
                }
            }
        } catch (Exception ignored) { }

        writeData(c, d);

        boolean sent = MainActivity.dispatchWidgetAction(kind, outAct);
        if (!sent) enqueue(c, kind, outAct);

        updateAll(c);
    }

    /* ==================== 内置种子点位 ==================== */

    private static JSONObject seedCache;

    private static JSONArray seedPins(String key) {
        try {
            if (seedCache == null) seedCache = new JSONObject(WidgetSeed.json());
            JSONArray a = seedCache.optJSONArray(key);
            if (a == null) a = seedCache.optJSONArray(DEFAULT_MAP_KEY);
            return a == null ? new JSONArray() : a;
        } catch (Exception e) { return new JSONArray(); }
    }

    /* ==================== 地图位图 ==================== */

    private static Bitmap baseCache;
    private static String baseCacheKey;
    private static Bitmap finalCache;
    private static String finalCacheSig;
    private static final Map<String, Bitmap> iconCache = new LinkedHashMap<String, Bitmap>();

    private static Bitmap loadMapBase(Context c, String key) {
        if (key == null || key.length() == 0) key = DEFAULT_MAP_KEY;
        if (baseCache != null && !baseCache.isRecycled() && key.equals(baseCacheKey)) return baseCache;
        InputStream in = null;
        try {
            in = c.getAssets().open("www/maps/" + key + ".webp");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src = BitmapFactory.decodeStream(in, null, o);
            if (src == null) return null;
            Bitmap scaled = Bitmap.createScaledBitmap(src, BMP_W, BMP_H, true);
            if (scaled != src) src.recycle();
            baseCache = scaled;
            baseCacheKey = key;
            return scaled;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** 解码学生头像并缩放成 ICON×ICON 的方形位图，带 LRU 上限缓存。 */
    private static Bitmap loadIcon(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        Bitmap b = iconCache.get(id);
        if (b != null && !b.isRecycled()) return b;
        InputStream in = null;
        try {
            in = c.getAssets().open("www/icons/" + id + ".webp");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap src = BitmapFactory.decodeStream(in, null, o);
            if (src == null) return null;
            Bitmap sc = Bitmap.createScaledBitmap(src, ICON, ICON, true);
            if (sc != src) src.recycle();
            if (iconCache.size() > 80) {
                java.util.Iterator<String> it = iconCache.keySet().iterator();
                if (it.hasNext()) { Bitmap old = iconCache.remove(it.next()); if (old != null) old.recycle(); }
            }
            iconCache.put(id, sc);
            return sc;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 在底图上叠加学生。pins 为 [u, v, on, "头像id"]；为空时用内置种子点位。
     * 在线 = 蓝色圆环，离线 = 灰色圆环 + 半透明头像；画不出头像时退化为实心圆点。
     */
    static Bitmap mapWithPins(Context c, String key, JSONArray pins) {
        Bitmap base = loadMapBase(c, key);
        if (base == null) return null;
        boolean seeded = (pins == null || pins.length() == 0);
        if (seeded) pins = seedPins(key);
        String sig = key + "|" + (pins == null ? "" : pins.toString()) + "|" + seeded;
        if (finalCache != null && !finalCache.isRecycled() && sig.equals(finalCacheSig)) return finalCache;

        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        if (pins == null || pins.length() == 0) { finalCache = out; finalCacheSig = sig; return out; }
        Canvas cv = new Canvas(out);
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setStyle(Paint.Style.FILL);
        white.setColor(0xFFFFFFFF);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2.2f);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = ICON / 2f;
        for (int i = 0; i < pins.length(); i++) {
            JSONArray p = pins.optJSONArray(i);
            if (p == null || p.length() < 2) continue;
            double u = p.optDouble(0, -1), v = p.optDouble(1, -1);
            if (u < 0 || v < 0 || u > 1 || v > 1) continue;
            boolean on = p.optInt(2, 0) != 0;
            String id = p.optString(3, "");
            float x = (float) (u * BMP_W), y = (float) (v * BMP_H);

            cv.drawCircle(x, y, r + 2.6f, white);          /* 白色底圈，保证浅色地图上也看得见 */
            Bitmap ic = loadIcon(c, id);
            if (ic != null) {
                BitmapShader sh = new BitmapShader(ic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix mx = new Matrix();
                mx.setTranslate(x - r, y - r);
                sh.setLocalMatrix(mx);
                Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
                ip.setShader(sh);
                if (!on) ip.setAlpha(150);                 /* 离线：头像变淡 */
                cv.drawCircle(x, y, r, ip);
            } else {
                dot.setColor(on ? 0xFF2F6FC4 : 0xFF9AA9B8);
                cv.drawCircle(x, y, r, dot);
            }
            edge.setColor(on ? 0xFF2F6FC4 : 0xFF9AA9B8);
            cv.drawCircle(x, y, r + 1.2f, edge);
        }
        finalCache = out;
        finalCacheSig = sig;
        return out;
    }

    /* ==================== 历史文本 ==================== */

    /** 把当前页的历史记录拼成多行文本。阿洛娜每条为 {"r","t"} JSON 字符串。 */
    private static String historyText(String kind, JSONArray log, int page, int pages, int n) {
        if (log == null || n == 0) {
            return "还没有历史记录 · 点右上「输入」开始"
                    + ("term".equals(kind) ? "敲命令" : "和阿洛娜对话");
        }
        int end = n - page * PAGE;
        int start = Math.max(0, end - PAGE);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            String s = log.optString(i, "");
            if (s.length() == 0) continue;
            if ("arona".equals(kind)) {
                String role = "阿洛娜", txt = s;
                try {
                    JSONObject o = new JSONObject(s);
                    role = "u".equals(o.optString("r", "a")) ? "老师" : "阿洛娜";
                    txt = o.optString("t", s);
                } catch (Exception ignored) { }
                s = role + "：" + txt;
            }
            if (s.length() > 46) s = s.substring(0, 46) + "…";
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /* ==================== RemoteViews 构建 ==================== */

    public static RemoteViews build(Context c, String kind, Bundle opts) {
        int layout;
        String[] shorts, longs, acts;
        if ("arona".equals(kind)) {
            layout = R.layout.widget_arona;
            shorts = ARONA_SHORT; longs = ARONA_LONG; acts = ARONA_ACT;
        } else if ("term".equals(kind)) {
            layout = R.layout.widget_term;
            shorts = TERM_SHORT; longs = TERM_LONG; acts = TERM_ACT;
        } else {
            layout = R.layout.widget_map;
            shorts = MAP_SHORT; longs = MAP_LONG; acts = MAP_ACT;
        }
        final boolean isMap = "map".equals(kind);

        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        JSONObject d = data(c);
        String map = d.optString("map", "");
        String total = d.optString("total", "0");
        String online = d.optString("online", "0");
        String line = d.optString("line", "");
        String term = d.optString("term", "");
        String status = d.optString("status", "");

        int w = sizeDp(opts, true, 180);
        int h = sizeDp(opts, false, 110);
        boolean wide = w >= 210;
        boolean tiny = w < 130;
        boolean tall = h >= 95;

        String main, sub;
        int pages = 1, page = 0, logN = 0;
        if (isMap) {
            main = map;
            sub = "在场 " + total + " · 在线 " + online + " · 学生头像已就位";
        } else {
            String lk = "arona".equals(kind) ? "aronaLog" : "termLog";
            String pk = "arona".equals(kind) ? "aronaPage" : "termPage";
            JSONArray log = d.optJSONArray(lk);
            logN = log == null ? 0 : log.length();
            pages = Math.max(1, (logN + PAGE - 1) / PAGE);
            page = d.optInt(pk, 0);
            if (page >= pages) page = pages - 1;
            if (page < 0) page = 0;
            main = historyText(kind, log, page, pages, logN);
            sub = "第 " + (page + 1) + "/" + pages + " 页 · 共 " + logN + " 条"
                    + (page > 0 ? "（更早）" : "（最新）");
        }
        if (status.length() > 0) sub = status;
        if (main == null || main.length() == 0) main = "什亭之匣";

        v.setTextViewText(R.id.wg_main, trim(main, isMap ? (wide ? 150 : 58) : 600));
        v.setTextViewText(R.id.wg_sub, trim(sub, wide ? 90 : 38));

        int mainSp = isMap ? (w >= 280 ? 19 : (w >= 150 ? 15 : 12))
                          : (w >= 280 ? 14 : (tiny ? 10 : 12));
        int subSp  = w >= 280 ? 12 : (tiny ? 9 : 10);
        int btnSp  = w >= 280 ? 13 : (tiny ? 10 : 12);
        v.setTextViewTextSize(R.id.wg_main, TypedValue.COMPLEX_UNIT_SP, mainSp);
        v.setTextViewTextSize(R.id.wg_sub, TypedValue.COMPLEX_UNIT_SP, subSp);
        v.setTextViewTextSize(R.id.wg_title, TypedValue.COMPLEX_UNIT_SP, tiny ? 10 : 11);

        v.setViewVisibility(R.id.wg_sub, tall ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.wg_open, tiny ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.wg_actions, View.VISIBLE);

        v.setViewVisibility(R.id.wg_input, isMap ? View.GONE : View.VISIBLE);
        if (!isMap) v.setOnClickPendingIntent(R.id.wg_input, inputPi(c, kind));

        /* 历史翻页条：仅阿洛娜 / 终端布局有这些 id */
        if (!isMap) {
            boolean showHist = tall && !tiny;
            v.setViewVisibility(R.id.wg_hist_bar, showHist ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.wg_hist_prev, showHist ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.wg_hist_next, showHist ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.wg_hist_info, showHist ? View.VISIBLE : View.GONE);
            if (showHist) {
                v.setTextViewText(R.id.wg_hist_info, (page + 1) + "/" + pages + " · 最新");
                v.setTextViewTextSize(R.id.wg_hist_info, TypedValue.COMPLEX_UNIT_SP, tiny ? 9 : 10);
                v.setTextViewTextSize(R.id.wg_hist_prev, TypedValue.COMPLEX_UNIT_SP, tiny ? 9 : 10);
                v.setTextViewTextSize(R.id.wg_hist_next, TypedValue.COMPLEX_UNIT_SP, tiny ? 9 : 10);
                v.setOnClickPendingIntent(R.id.wg_hist_prev, actionPi(c, kind, "hist_prev", 5));
                v.setOnClickPendingIntent(R.id.wg_hist_next, actionPi(c, kind, "hist_next", 6));
                v.setOnClickPendingIntent(R.id.wg_hist_info, actionPi(c, kind, "hist_latest", 7));
            }
        }

        /* 地图画面：只在含 wg_map 的地图布局里操作 */
        if (isMap) {
            String key = d.optString("mapKey", DEFAULT_MAP_KEY);
            if (key.length() == 0) key = DEFAULT_MAP_KEY;
            JSONArray pins = d.optJSONArray("pins");
            Bitmap bmp = (w >= 100 && h >= 70) ? mapWithPins(c, key, pins) : null;
            if (bmp != null) {
                v.setImageViewBitmap(R.id.wg_map, bmp);
                v.setViewVisibility(R.id.wg_map, View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.wg_map, View.GONE);
            }
        }

        int[] ids = { R.id.wg_btn1, R.id.wg_btn2, R.id.wg_btn3, R.id.wg_btn4 };
        for (int i = 0; i < 4; i++) {
            if (i >= acts.length) {
                v.setViewVisibility(ids[i], View.GONE);
                continue;
            }
            v.setViewVisibility(ids[i], View.VISIBLE);
            v.setTextViewText(ids[i], wide ? longs[i] : shorts[i]);
            v.setTextViewTextSize(ids[i], TypedValue.COMPLEX_UNIT_SP, btnSp);
            v.setOnClickPendingIntent(ids[i], actionPi(c, kind, acts[i], i));
        }

        PendingIntent open = openPi(c, kind);
        v.setOnClickPendingIntent(R.id.wg_open, open);
        v.setOnClickPendingIntent(R.id.wg_title, open);
        v.setOnClickPendingIntent(R.id.wg_root, open);
        return v;
    }

    public static RemoteViews build(Context c, String kind) {
        return build(c, kind, null);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static int sizeDp(Bundle o, boolean width, int fallback) {
        if (o == null) return fallback;
        int n = o.getInt(width ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                              : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (n <= 0) n = o.getInt(width ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                                      : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return n > 0 ? n : fallback;
    }

    /* ==================== PendingIntent ==================== */

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private static int kindCode(String kind) {
        if ("arona".equals(kind)) return 2;
        if ("term".equals(kind)) return 3;
        return 1;
    }

    private static Class<?> providerClass(String kind) {
        if ("arona".equals(kind)) return AronaWidget.class;
        if ("term".equals(kind)) return TermWidget.class;
        return MapWidget.class;
    }

    private static PendingIntent actionPi(Context c, String kind, String act, int idx) {
        Intent i = new Intent(c, providerClass(kind))
                .setAction(ACTION_WIDGET)
                .putExtra("kind", kind)
                .putExtra("wg_action", act);
        int req = kindCode(kind) * 100 + 10 + idx;
        return PendingIntent.getBroadcast(c, req, i, piFlags());
    }

    private static PendingIntent openPi(Context c, String kind) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra("widget_open", kind)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int req = kindCode(kind) * 100 + 90;
        return PendingIntent.getActivity(c, req, i, piFlags());
    }

    private static PendingIntent inputPi(Context c, String kind) {
        Intent i = new Intent(c, WidgetInputActivity.class)
                .putExtra(WidgetInputActivity.EXTRA_KIND, kind)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int req = kindCode(kind) * 100 + 70;
        return PendingIntent.getActivity(c, req, i, piFlags());
    }

    /* ==================== 刷新 ==================== */

    public static void updateAll(Context c) {
        if (c == null) return;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            update(c, m, MapWidget.class, "map");
            update(c, m, AronaWidget.class, "arona");
            update(c, m, TermWidget.class, "term");
        } catch (Exception ignored) { }
    }

    private static void update(Context c, AppWidgetManager m, Class<?> cls, String kind) {
        try {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            if (ids == null) return;
            for (int id : ids) {
                Bundle o = null;
                try { o = m.getAppWidgetOptions(id); } catch (Exception ignored) { }
                m.updateAppWidget(id, build(c, kind, o));
            }
        } catch (Exception ignored) { }
    }

    public static void updateOne(Context c, AppWidgetManager m, int id, String kind, Bundle opts) {
        try { m.updateAppWidget(id, build(c, kind, opts)); }
        catch (Exception ignored) { }
    }
}
