package com.webtoapp.standalone;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * 依存ライブラリなしの「引っ張って更新」(v2.2)。
 *
 * SwipeRefreshLayout は androidx 依存のためテンプレートには入れられない。
 * そこで WebView がいちばん上までスクロールされている状態での下向きドラッグを
 * 親ビューで拾い、中身を少し下げてインジケータを出すだけの最小実装にしている。
 *
 * 失敗してもアプリを落とさないことを優先し、全処理を try-catch で保護する。
 */
public class PullToRefreshLayout extends FrameLayout {

    /** 更新要求 */
    public interface OnRefreshListener {
        void onRefresh();
    }

    private static final float MAX_PULL_DP = 160f;
    private static final float THRESHOLD_DP = 90f;

    private View content;
    private View indicator;
    private OnRefreshListener listener;

    private boolean enabled = true;
    private boolean refreshing = false;
    private boolean tracking = false;

    private float downY;
    private float pull;
    private int touchSlop;
    private float maxPull;
    private float threshold;

    public PullToRefreshLayout(Context context) {
        super(context);
        try {
            float density = getResources().getDisplayMetrics().density;
            maxPull = MAX_PULL_DP * density;
            threshold = THRESHOLD_DP * density;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        } catch (Throwable t) {
            maxPull = 400f;
            threshold = 240f;
            touchSlop = 24;
        }
    }

    public void setContent(View view) {
        content = view;
        addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setIndicator(View view) {
        indicator = view;
        if (view == null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (24 * getResources().getDisplayMetrics().density);
        view.setVisibility(View.GONE);
        addView(view, lp);
    }

    public void setOnRefreshListener(OnRefreshListener l) {
        listener = l;
    }

    public void setPullEnabled(boolean value) {
        enabled = value;
        if (!value) reset(false);
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    /** 中身がいちばん上にあるか (= 引っ張って更新してよい状態か)。 */
    private boolean canChildScrollUp() {
        if (content == null) return false;
        try {
            if (content.getScrollY() > 0) return true;
            return content.canScrollVertically(-1);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        try {
            if (!enabled || refreshing || content == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    tracking = !canChildScrollUp();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!tracking) return false;
                    if (ev.getY() - downY > touchSlop) return true;
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    return false;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        try {
            if (!enabled || content == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    tracking = true;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!tracking) return false;
                    float dy = ev.getY() - downY;
                    if (dy < 0) dy = 0;
                    // 引くほど抵抗が増える (maxPull を超えない)
                    float p = (float) (maxPull * (1.0 - Math.exp(-dy / (maxPull * 0.85))));
                    applyPull(p);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    if (pull >= threshold) startRefresh();
                    else reset(true);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            reset(false);
            return false;
        }
    }

    private void applyPull(float p) {
        pull = p;
        try {
            content.setTranslationY(p);
            if (indicator != null) {
                indicator.setVisibility(View.VISIBLE);
                indicator.setAlpha(Math.min(1f, p / Math.max(1f, threshold)));
            }
        } catch (Throwable ignored) {}
    }

    private void startRefresh() {
        refreshing = true;
        try {
            if (indicator != null) {
                indicator.setVisibility(View.VISIBLE);
                indicator.setAlpha(1f);
            }
            animateContent(0f);
            if (listener != null) listener.onRefresh();
        } catch (Throwable ignored) {}
    }

    /** ページの読み込みが終わったら呼ぶ。 */
    public void finishRefresh() {
        if (!refreshing) return;
        refreshing = false;
        try {
            if (indicator != null) indicator.setVisibility(View.GONE);
        } catch (Throwable ignored) {}
    }

    /** 引っ張り位置を戻す。animated=false なら即座に。 */
    private void reset(boolean animated) {
        pull = 0f;
        if (indicator != null) {
            try {
                if (!refreshing) indicator.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
        }
        if (content == null) return;
        if (animated) animateContent(0f);
        else {
            try {
                content.setTranslationY(0f);
            } catch (Throwable ignored) {}
        }
    }

    private void animateContent(float to) {
        try {
            final View target = content;
            if (target == null) return;
            float from = target.getTranslationY();
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
            anim.setDuration(180);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                try {
                    target.setTranslationY((Float) a.getAnimatedValue());
                } catch (Throwable ignored) {}
            });
            anim.start();
        } catch (Throwable t) {
            try {
                if (content != null) content.setTranslationY(to);
            } catch (Throwable ignored2) {}
        }
    }
}
