package com.voxivoid.recipelab;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * The layout's root: a FrameLayout that says when it has drawn. Every screen — the panel, the brand list, the menus —
 * is drawn through it, so MainActivity times a key from its event time to the end of the draw it caused in one place
 * ({@link CardLog.Timing}, the PERF lines). What it measures ends where the app's drawing does: putting the frame on
 * the display comes after.
 */
public class TimedFrame extends FrameLayout {
    /** told each time the tree has been drawn: when that draw began and ended, in uptime ms */
    public interface Listener { void drawn(long start, long end); }

    private Listener listener;

    public TimedFrame(Context c, AttributeSet a) { super(c, a); }

    public void setListener(Listener l) { listener = l; }

    @Override
    protected void dispatchDraw(Canvas c) {
        long start = SystemClock.uptimeMillis();
        super.dispatchDraw(c);
        if (listener != null) listener.drawn(start, SystemClock.uptimeMillis());
    }
}
