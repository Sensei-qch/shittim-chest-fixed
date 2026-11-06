package com.shittim.chest;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 桌面小组件：阿洛娜对话。可在桌面直接让阿洛娜说话或发起 AI 提问。 */
public class AronaWidget extends AppWidgetProvider {
    private static final String KIND = "arona";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i != null && WidgetHelper.ACTION_WIDGET.equals(i.getAction())) {
            WidgetHelper.handleAction(c, i);
            return;
        }
        super.onReceive(c, i);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        if (ids == null) return;
        for (int id : ids) {
            Bundle o = null;
            try { o = m.getAppWidgetOptions(id); } catch (Exception ignored) { }
            WidgetHelper.updateOne(c, m, id, KIND, o);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle newOptions) {
        WidgetHelper.updateOne(c, m, id, KIND, newOptions);
        super.onAppWidgetOptionsChanged(c, m, id, newOptions);
    }
}
