package com.shittim.chest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 桌面小组件的文字输入弹窗。
 *
 * Android 的 App Widget 基于 RemoteViews，不支持 EditText，因此无法在小组件上直接打字。
 * 这里用一个浮动对话框 Activity 承接输入：点小组件右上「输入」→ 弹出本界面 →
 * 软键盘自动升起 → 内容经 {@link WidgetHelper#submitInput} 立即回显到桌面并派发给
 * WebView 执行（应用未运行时入队，下次打开补执行）→ 结果自动回刷桌面。
 *
 * 本 Activity 独立于主界面任务栈、不进最近任务、退出即销毁，不影响 App 主流程。
 */
public class WidgetInputActivity extends Activity {

    static final String EXTRA_KIND = "input_kind";   /* arona / term */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String kind = getIntent() == null ? "arona" : getIntent().getStringExtra(EXTRA_KIND);
        final boolean arona = !"term".equals(kind);
        final int dp = Math.max(1, Math.round(getResources().getDisplayMetrics().density));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20 * dp, 18 * dp, 20 * dp, 16 * dp);

        TextView title = new TextView(this);
        title.setText(getString(arona ? R.string.input_arona_title : R.string.input_term_title));
        title.setTextColor(0xFF2F5FA8);
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText(getString(arona ? R.string.input_arona_note : R.string.input_term_note));
        note.setTextColor(0xFF7089A6);
        note.setTextSize(12);
        note.setPadding(0, 6 * dp, 0, 12 * dp);
        root.addView(note);

        final EditText et = new EditText(this);
        et.setHint(getString(arona ? R.string.input_arona_hint : R.string.input_term_hint));
        et.setTextColor(0xFF27435F);
        et.setHintTextColor(0xFF9AB3CC);
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setInputType(arona ? InputType.TYPE_CLASS_TEXT
                             : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        et.setImeOptions(EditorInfo.IME_ACTION_SEND);
        et.setBackgroundResource(R.drawable.widget_btn);
        et.setPadding(12 * dp, 10 * dp, 12 * dp, 10 * dp);
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 14 * dp, 0, 0);

        TextView spacer = new TextView(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = makeButton(getString(R.string.input_cancel), false, dp);
        TextView send = makeButton(getString(R.string.input_send), true, dp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = 10 * dp;
        row.addView(cancel, cp);
        row.addView(send);
        root.addView(row);

        setContentView(root);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setFinishOnTouchOutside(true);

        final Runnable submit = new Runnable() {
            @Override public void run() {
                String t = et.getText() == null ? "" : et.getText().toString();
                WidgetHelper.submitInput(WidgetInputActivity.this, kind, t);
                finish();
            }
        };
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit.run(); }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
                        || (e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            && e.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    submit.run();
                    return true;
                }
                return false;
            }
        });

        et.requestFocus();
        et.post(new Runnable() {
            @Override public void run() {
                try {
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception ignored) { }
            }
        });
    }

    private TextView makeButton(String text, boolean primary, int dp) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        if (primary) {
            v.setTextColor(0xFFFFFFFF);
            v.setBackgroundResource(R.drawable.widget_btn_primary);
        } else {
            v.setTextColor(0xFF2F5FA8);
            v.setBackgroundResource(R.drawable.widget_btn);
        }
        v.setPadding(18 * dp, 8 * dp, 18 * dp, 8 * dp);
        return v;
    }
}
