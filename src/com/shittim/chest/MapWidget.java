package com.shittim.chest;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 桌面小组件：地图。可在桌面直接切换 / 更新地图，点「打开」进入应用。 */
public class MapWidget extends AppWidgetProvider {
    private static final String KIND = "map";

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
