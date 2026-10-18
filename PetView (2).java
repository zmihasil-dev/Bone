package pet.margyt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/**
 * The overlay: finds a profile avatar on the screen, draws a hammer you can drag onto it
 * (or just tap the avatar), plays the smash and finally shows the looksmaxxed avatar on top.
 * (The file name is kept from the earlier plugins so the GitHub workflow works unchanged.)
 */
final class PetView extends View {

    private static final int NEED_HITS = 3;
    private static final float WIND = 0.16f;
    private static final float STRIKE = 0.26f;
    private static final float HOLD = 0.36f;
    private static final float TH_BACK = -170f;
    private static final float TH_HIT = -22f;
    private static final int GOLD = 0xFFFFD36B;

    private static final class Shard {
        String s;
        float x, y, vx, vy, g, life, max, size;
    }

    private static final class Pop {
        String s;
        float x, y, life, max, size;
        int color;
    }

    private final float d;
    private final float slop;
    private final boolean ru;
    private final Random rnd = new Random();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Rect vis = new Rect();
    private final int[] loc = new int[2];
    private final int[] myLoc = new int[2];
    private final ArrayList<Shard> shards = new ArrayList<Shard>();
    private final ArrayList<Pop> pops = new ArrayList<Pop>();
    private final ArrayList<float[]> cracks = new ArrayList<float[]>();
    private final float[] pose = new float[3];

    // target avatar
    private View target;
    private Object lastDrawable;
    private boolean have;
    private float ax, ay, aw, ah;
    private float visL, visT, visR, visB;
    private float len = 70f;

    // state
    private int hits;
    private boolean transformed;
    private Bitmap chad;
    private float t, searchT;
    private float swing = -1f;
    private boolean impacted;
    private float hitU = 0.5f, hitV = 0.7f;
    private float transT = -1f;
    private float shakeT;
    private float flashT, flashX, flashY;
    private boolean shaking;
    private float origTx, origTy;

    // hammer
    private float curPx, curPy, curTh = -128f;
    private boolean poseReady;
    private float startPx, startPy, startTh;
    private boolean dragging;
    private float dragX, dragY;

    // touch
    private boolean tracking;
    private float downX, downY;
    private boolean downOnHammer;

    // loop
    private boolean running;
    private long last;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - last) / 1000f);
            last = now;
            try {
                step(dt);
            } catch (Throwable e) {
                // a broken frame must never take TikTok down
            }
            invalidate();
            postOnAnimation(this);
        }
    };

    PetView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        slop = Math.max(ViewConfiguration.get(c).getScaledTouchSlop(), 8f * d);
        ru = "ru".equals(Locale.getDefault().getLanguage());
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setWillNotDraw(false);
        setClickable(false);
    }

    void stop() {
        running = false;
        removeCallbacks(frame);
        restoreShake();
        target = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        last = SystemClock.uptimeMillis();
        postOnAnimation(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(frame);
        restoreShake();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ finding the avatar

    private static boolean avatarLike(View v) {
        if (v instanceof SurfaceView || v instanceof TextureView) {
            return false;
        }
        if (v instanceof ImageView) {
            return true;
        }
        if (v instanceof ViewGroup) {
            return false;
        }
        String n = v.getClass().getName();
        return n.contains("Avatar") || n.contains("Drawee");
    }

    private View best;
    private float bestArea;

    private void walk(View v, float screenH) {
        if (v == this || v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (avatarLike(v)) {
            float lo = 56f * d;
            float hi = 200f * d;
            int w = v.getWidth();
            int h = v.getHeight();
            if (w < lo || h < lo || w > hi || h > hi || Math.abs(w - h) > 6f * d) {
                return;
            }
            if (!v.isShown() || !v.getGlobalVisibleRect(vis)) {
                return;
            }
            if ((float) vis.width() * vis.height() < 0.6f * w * h) {
                return;
            }
            v.getLocationOnScreen(loc);
            if (loc[1] > screenH * 0.6f) {
                return;
            }
            float area = (float) w * h;
            if (area > bestArea) {
                bestArea = area;
                best = v;
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), screenH);
            }
        }
    }

    private View findAvatar() {
        if (!(getParent() instanceof ViewGroup)) {
            return null;
        }
        best = null;
        bestArea = 0f;
        walk((ViewGroup) getParent(), getHeight());
        return best;
    }

    private boolean isValid(View v) {
        return v.isAttachedToWindow() && v.isShown() && v.getWidth() > 0 && v.getHeight() > 0;
    }

    private static Object drawableOf(View v) {
        return v instanceof ImageView ? ((ImageView) v).getDrawable() : null;
    }

    private void acquire(View v) {
        target = v;
        lastDrawable = drawableOf(v);
        resetState();
        addPop(ru ? "Перетащи молоток на аватарку или тапни по ней" : "Drag the hammer onto the avatar or tap it",
                0f, 0f, 0xFFFFFFFF, 14f, 4f, true);
    }

    private void dropTarget() {
        restoreShake();
        target = null;
        have = false;
        resetState();
        dragging = false;
        tracking = false;
    }

    private void resetState() {
        hits = 0;
        transformed = false;
        chad = null;
        cracks.clear();
        swing = -1f;
        transT = -1f;
        impacted = false;
    }

    private void restoreShake() {
        if (shaking && target != null) {
            target.setTranslationX(origTx);
            target.setTranslationY(origTy);
        }
        shaking = false;
        shakeT = 0f;
    }

    private void updateGeometry() {
        target.getLocationOnScreen(loc);
        getLocationOnScreen(myLoc);
        float sx = target.getScaleX();
        float sy = target.getScaleY();
        aw = target.getWidth() * sx;
        ah = target.getHeight() * sy;
        ax = loc[0] - myLoc[0] + (target.getWidth() - aw) / 2f;
        ay = loc[1] - myLoc[1] + (target.getHeight() - ah) / 2f;
        have = target.getGlobalVisibleRect(vis) && vis.width() > 0 && vis.height() > 0;
        visL = vis.left - myLoc[0];
        visT = vis.top - myLoc[1];
        visR = vis.right - myLoc[0];
        visB = vis.bottom - myLoc[1];
        len = Math.max(60f * d, Math.min(100f * d, Math.min(aw, ah) * 0.85f));
    }

    // ------------------------------------------------------------------ hammer maths

    private float idlePx() {
        return getWidth() * 0.80f;
    }

    private float idlePy() {
        return getHeight() * 0.80f;
    }

    private float idleTh() {
        return -128f + 5f * (float) Math.sin(t * 2.5f);
    }

    /** Head centre of the hammer for a pivot and angle. */
    private float headX(float px, float th) {
        return px - len * (float) Math.sin(Math.toRadians(th));
    }

    private float headY(float py, float th) {
        return py + len * (float) Math.cos(Math.toRadians(th));
    }

    private float pivotX(float hx, float th) {
        return hx + len * (float) Math.sin(Math.toRadians(th));
    }

    private float pivotY(float hy, float th) {
        return hy - len * (float) Math.cos(Math.toRadians(th));
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    private void updateHammer(float dt) {
        if (!poseReady && getWidth() > 0) {
            curPx = idlePx();
            curPy = idlePy();
            curTh = idleTh();
            poseReady = true;
        }
        if (swing >= 0f) {
            float hx = ax + hitU * aw;
            float hy = ay + hitV * ah;
            float spx = pivotX(hx, TH_HIT);
            float spy = pivotY(hy, TH_HIT);
            if (swing < WIND) {
                float k = swing / WIND;
                k = 1f - (1f - k) * (1f - k);
                curPx = lerp(startPx, spx, k);
                curPy = lerp(startPy, spy, k);
                curTh = lerp(startTh, TH_BACK, k);
            } else if (swing < STRIKE) {
                float k = (swing - WIND) / (STRIKE - WIND);
                k = k * k;
                curPx = spx;
                curPy = spy;
                curTh = lerp(TH_BACK, TH_HIT, k);
            } else {
                curPx = spx;
                curPy = spy;
                curTh = TH_HIT;
            }
            return;
        }
        float tx;
        float ty;
        float tth;
        float rate;
        if (dragging) {
            tth = TH_HIT;
            tx = pivotX(dragX, tth);
            ty = pivotY(dragY, tth);
            rate = 24f;
        } else {
            tx = idlePx();
            ty = idlePy();
            tth = idleTh();
            rate = 9f;
        }
        float k = Math.min(1f, dt * rate);
        curPx += (tx - curPx) * k;
        curPy += (ty - curPy) * k;
        curTh += (tth - curTh) * k;
    }

    // ------------------------------------------------------------------ simulation

    private void step(float dt) {
        t += dt;
        searchT -= dt;
        if (target != null && !isValid(target)) {
            dropTarget();
        }
        if (target == null && searchT <= 0f) {
            searchT = 0.45f;
            View f = findAvatar();
            if (f != null) {
                acquire(f);
            }
        }
        if (target != null) {
            updateGeometry();
            Object cur = drawableOf(target);
            if (cur != lastDrawable) {
                lastDrawable = cur;
                if (hits == 0 || transformed) {
                    restoreShake();
                    resetState();
                }
            }
        } else {
            have = false;
        }

        if (have) {
            if (swing >= 0f) {
                swing += dt;
                if (!impacted && swing >= STRIKE) {
                    impacted = true;
                    onImpact();
                }
                if (swing >= HOLD) {
                    swing = -1f;
                    impacted = false;
                }
            }
            updateHammer(dt);
        }
        if (transT >= 0f) {
            transT += dt;
        }
        if (flashT > 0f) {
            flashT -= dt;
        }

        if (shakeT > 0f && target != null) {
            if (!shaking) {
                shaking = true;
                origTx = target.getTranslationX();
                origTy = target.getTranslationY();
            }
            float a = 7f * d * (shakeT / 0.35f);
            target.setTranslationX(origTx + (rnd.nextFloat() - 0.5f) * 2f * a);
            target.setTranslationY(origTy + (rnd.nextFloat() - 0.5f) * 2f * a);
            shakeT -= dt;
            if (shakeT <= 0f) {
                restoreShake();
            }
        }

        for (int i = shards.size() - 1; i >= 0; i--) {
            Shard s = shards.get(i);
            s.life -= dt;
            if (s.life <= 0f) {
                shards.remove(i);
                continue;
            }
            s.vy += s.g * dt;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
        }
        for (int i = pops.size() - 1; i >= 0; i--) {
            Pop p = pops.get(i);
            p.life -= dt;
            if (p.life <= 0f) {
                pops.remove(i);
            }
        }
    }

    private void burst(String s, int n, float x, float y, float speedDp, float sizeDp, float gravityDp) {
        for (int i = 0; i < n && shards.size() < 80; i++) {
            Shard p = new Shard();
            double ang = rnd.nextDouble() * Math.PI * 2.0;
            float sp = (0.35f + rnd.nextFloat() * 0.65f) * speedDp * d;
            p.s = s;
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * sp;
            p.vy = (float) Math.sin(ang) * sp - speedDp * 0.3f * d;
            p.g = gravityDp * d;
            p.max = 0.7f + rnd.nextFloat() * 0.7f;
            p.life = p.max;
            p.size = sizeDp * d * (0.7f + rnd.nextFloat() * 0.6f);
            shards.add(p);
        }
    }

    private void addPop(String s, float x, float y, int color, float sizeDp, float secs, boolean anchorBelow) {
        Pop p = new Pop();
        p.s = s;
        p.x = x;
        p.y = y;
        p.color = color;
        p.size = sizeDp * d;
        p.max = secs;
        p.life = secs;
        p.x = anchorBelow ? Float.NaN : x;
        pops.add(p);
    }

    private void addCrack(float u, float v) {
        int branches = 4 + rnd.nextInt(3);
        for (int b = 0; b < branches; b++) {
            float[] pts = new float[8];
            pts[0] = u;
            pts[1] = v;
            float ang = rnd.nextFloat() * 6.2832f;
            float x = u;
            float y = v;
            for (int k = 1; k < 4; k++) {
                ang += (rnd.nextFloat() - 0.5f) * 1.2f;
                float step = 0.05f + rnd.nextFloat() * 0.09f;
                x += (float) Math.cos(ang) * step;
                y += (float) Math.sin(ang) * step;
                pts[k * 2] = x;
                pts[k * 2 + 1] = y;
            }
            cracks.add(pts);
        }
    }

    private void onImpact() {
        hits++;
        float hx = ax + hitU * aw;
        float hy = ay + hitV * ah;
        addCrack(hitU, hitV);
        burst("🦴", 7, hx, hy, 230f, 15f, 520f);
        String[] words = {"CRACK!", "SNAP!", "BONESMASH!"};
        addPop(words[Math.min(hits - 1, 2)], hx, hy - 0.45f * ah, 0xFFFFFFFF, 26f, 0.9f, false);
        shakeT = 0.35f;
        flashT = 0.14f;
        flashX = hx;
        flashY = hy;
        HammerSound.play(0);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (hits >= NEED_HITS) {
            startTransform();
        }
    }

    private Bitmap snapshot(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            float tx = v.getTranslationX();
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            v.setTranslationX(tx);
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    private void startTransform() {
        restoreShake();
        Bitmap snap = snapshot(target);
        chad = null;
        if (snap != null) {
            try {
                chad = Chad.make(snap);
            } catch (Throwable e) {
                chad = null;
            }
        }
        transformed = true;
        transT = 0f;
        cracks.clear();
        HammerSound.play(1);
        float cx = ax + aw / 2f;
        float cy = ay + ah / 2f;
        burst("✨", 22, cx, cy, 320f, 18f, 120f);
        burst("💎", 5, cx, cy, 240f, 16f, 300f);
        addPop("LOOKSMAXXED!", cx, ay - 0.15f * ah, GOLD, 24f, 1.8f, false);
    }

    private void hit(float x, float y) {
        if (swing >= 0f || transformed || !have) {
            return;
        }
        hitU = Math.max(0.18f, Math.min(0.82f, (x - ax) / aw));
        hitV = Math.max(0.25f, Math.min(0.9f, (y - ay) / ah));
        startPx = curPx;
        startPy = curPy;
        startTh = curTh;
        swing = 0f;
        impacted = false;
    }

    // ------------------------------------------------------------------ touch

    private boolean onHammer(float x, float y) {
        float hx = headX(curPx, curTh);
        float hy = headY(curPy, curTh);
        float mx = (hx + curPx) / 2f;
        float my = (hy + curPy) / 2f;
        float r1 = 38f * d;
        float r2 = 26f * d;
        return (x - hx) * (x - hx) + (y - hy) * (y - hy) <= r1 * r1
                || (x - mx) * (x - mx) + (y - my) * (y - my) <= r2 * r2;
    }

    private boolean onAvatar(float x, float y) {
        float m = 8f * d;
        return x >= ax - m && x <= ax + aw + m && y >= ay - m && y <= ay + ah + m;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (target == null || !have || transformed) {
            return false;
        }
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (swing < 0f && onHammer(x, y)) {
                    dragging = true;
                    dragX = x;
                    dragY = y;
                    tracking = true;
                    downOnHammer = true;
                } else if (onAvatar(x, y)) {
                    tracking = true;
                    downOnHammer = false;
                    downX = x;
                    downY = y;
                } else {
                    return false;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) {
                    return false;
                }
                if (downOnHammer) {
                    dragX = x;
                    dragY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) {
                    return false;
                }
                tracking = false;
                if (downOnHammer) {
                    dragging = false;
                    if (onAvatar(x, y)) {
                        hit(x, y);
                    }
                } else if (Math.hypot(x - downX, y - downY) < slop) {
                    hit(x, y);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                dragging = false;
                return true;
            default:
                return tracking;
        }
    }

    // ------------------------------------------------------------------ drawing

    private static int a255(float v) {
        int i = (int) (v * 255f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private void drawOutlined(Canvas c, String s, float x, float y, float size, int color,
                              float rot, float alpha) {
        c.save();
        c.rotate(rot, x, y);
        textP.setTextSize(size);
        textP.setStyle(Paint.Style.STROKE);
        textP.setStrokeWidth(size * 0.18f);
        textP.setColor(0xFF000000);
        textP.setAlpha(a255(alpha));
        c.drawText(s, x, y, textP);
        textP.setStyle(Paint.Style.FILL);
        textP.setColor(color);
        textP.setAlpha(a255(alpha));
        c.drawText(s, x, y, textP);
        c.restore();
        textP.setAlpha(255);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (target == null || !have || getWidth() <= 0) {
            return;
        }
        float cx = ax + aw / 2f;
        float cy = ay + ah / 2f;
        float r = Math.min(aw, ah) / 2f;

        // everything that sits on the avatar is clipped to the part of it that is visible
        c.save();
        c.clipRect(visL, visT, visR, visB);

        if (transformed) {
            float k = transT < 0.4f ? 1f + 0.18f * (float) Math.sin(Math.PI * transT / 0.4f) : 1f;
            c.save();
            c.translate(cx, cy);
            c.scale(k, k);
            path.reset();
            path.addCircle(0f, 0f, r, Path.Direction.CW);
            c.clipPath(path);
            if (chad != null) {
                rect.set(-r, -r, r, r);
                c.drawBitmap(chad, null, rect, bmpP);
            }
            c.restore();

            float glow = 0.55f + 0.45f * (float) Math.sin(t * 4f);
            stroke.setColor(GOLD);
            stroke.setStrokeWidth(3f * d);
            stroke.setAlpha(a255(glow));
            c.drawCircle(cx, cy, r * k + 1.5f * d, stroke);
            stroke.setStrokeWidth(8f * d);
            stroke.setAlpha(a255(glow * 0.25f));
            c.drawCircle(cx, cy, r * k + 3f * d, stroke);
            stroke.setAlpha(255);

            if (transT >= 0f && transT < 0.5f) {
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(a255(1f - transT / 0.5f));
                c.drawCircle(cx, cy, r * 1.05f, fill);
                fill.setAlpha(255);
            }
        } else {
            // cracks stay on the avatar, inside its circle
            c.save();
            path.reset();
            path.addCircle(cx, cy, r, Path.Direction.CW);
            c.clipPath(path);
            for (int i = 0; i < cracks.size(); i++) {
                float[] p = cracks.get(i);
                path.reset();
                path.moveTo(ax + p[0] * aw, ay + p[1] * ah);
                for (int k = 1; k < 4; k++) {
                    path.lineTo(ax + p[k * 2] * aw, ay + p[k * 2 + 1] * ah);
                }
                stroke.setColor(0xCC000000);
                stroke.setStrokeWidth(3.2f * d);
                c.drawPath(path, stroke);
                stroke.setColor(0xFFFFFFFF);
                stroke.setStrokeWidth(1.4f * d);
                c.drawPath(path, stroke);
            }
            if (flashT > 0f) {
                float fr = r * (0.35f + (0.14f - flashT) * 4f);
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(a255(flashT / 0.14f * 0.85f));
                c.drawCircle(flashX, flashY, fr, fill);
                fill.setAlpha(255);
            }
            c.restore();
        }
        c.restore();

        // the badge on the finished avatar
        if (transformed && transT > 0.3f) {
            String label = "LOOKSMAXXED 10/10";
            textP.setTextSize(11f * d);
            textP.setStyle(Paint.Style.FILL);
            float w = textP.measureText(label) + 18f * d;
            float h = 20f * d;
            float by = ay + ah - 2f * d;
            rect.set(cx - w / 2f, by - h / 2f, cx + w / 2f, by + h / 2f);
            fill.setColor(0xE6101010);
            c.drawRoundRect(rect, h / 2f, h / 2f, fill);
            stroke.setColor(GOLD);
            stroke.setStrokeWidth(1.5f * d);
            c.drawRoundRect(rect, h / 2f, h / 2f, stroke);
            textP.setColor(GOLD);
            c.drawText(label, cx, by + 4f * d, textP);
        }

        // the hammer
        float hide = transformed ? Math.min(1f, transT / 0.5f) : 0f;
        if (hide < 1f) {
            float sc = 1f - hide;
            c.save();
            c.scale(sc, sc, curPx, curPy);
            drawHammer(c, curPx, curPy, curTh);
            c.restore();
        }

        // flying bones and sparkles
        for (int i = 0; i < shards.size(); i++) {
            Shard s = shards.get(i);
            textP.setStyle(Paint.Style.FILL);
            textP.setTextSize(s.size);
            textP.setColor(0xFFFFFFFF);
            textP.setAlpha(a255(Math.min(1f, s.life / (s.max * 0.5f))));
            c.drawText(s.s, s.x, s.y, textP);
        }
        textP.setAlpha(255);

        // words
        for (int i = 0; i < pops.size(); i++) {
            Pop p = pops.get(i);
            float f = 1f - p.life / p.max;
            float scale = f < 0.15f ? 0.5f + f / 0.15f * 0.7f : 1.2f - 0.2f * f;
            float alpha = p.life / p.max < 0.3f ? p.life / p.max / 0.3f : 1f;
            float px = Float.isNaN(p.x) ? cx : p.x;
            float py = Float.isNaN(p.x) ? ay + ah + 24f * d : p.y - f * 18f * d;
            drawOutlined(c, p.s, px, py, p.size * scale, p.color, Float.isNaN(p.x) ? 0f : -8f, alpha);
        }
    }

    private void drawHammer(Canvas c, float px, float py, float th) {
        c.save();
        c.translate(px, py);
        c.rotate(th);
        fill.setStyle(Paint.Style.FILL);
        // handle
        rect.set(-5f * d, -36f * d, 5f * d, len);
        fill.setColor(0xFF9B6A3C);
        c.drawRoundRect(rect, 5f * d, 5f * d, fill);
        rect.set(-1.6f * d, -32f * d, 1.6f * d, len - 8f * d);
        fill.setColor(0x44FFFFFF);
        c.drawRoundRect(rect, 2f * d, 2f * d, fill);
        // grip bands
        fill.setColor(0xFF3A2A1E);
        for (int i = 0; i < 4; i++) {
            rect.set(-5.6f * d, (-34f + i * 9f) * d, 5.6f * d, (-30f + i * 9f) * d);
            c.drawRect(rect, fill);
        }
        // head
        rect.set(-30f * d, len - 16f * d, 30f * d, len + 16f * d);
        fill.setColor(0xFF8E98A3);
        c.drawRoundRect(rect, 6f * d, 6f * d, fill);
        rect.set(-28f * d, len - 14f * d, 28f * d, len - 8f * d);
        fill.setColor(0x77FFFFFF);
        c.drawRoundRect(rect, 3f * d, 3f * d, fill);
        rect.set(-30f * d, len + 8f * d, 30f * d, len + 16f * d);
        fill.setColor(0x44000000);
        c.drawRoundRect(rect, 4f * d, 4f * d, fill);
        rect.set(-30f * d, len - 16f * d, -23f * d, len + 16f * d);
        fill.setColor(0x33000000);
        c.drawRoundRect(rect, 5f * d, 5f * d, fill);
        c.restore();
    }
}

/** Turns an avatar into the looksmaxxed version: hollow cheeks, cheekbones, sharper jaw, contrast. */
final class Chad {

    private Chad() {
    }

    private static float gauss(float x, float m, float s) {
        float z = (x - m) / s;
        return (float) Math.exp(-0.5f * z * z);
    }

    private static float sstep(float a, float b, float x) {
        float t = (x - a) / (b - a);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    static Bitmap make(Bitmap src) {
        final int s = 384;
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap in = Bitmap.createScaledBitmap(sq, s, s, true);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        // colour: a bit desaturated, harder contrast, slightly cold
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.5f);
        float k = 1.3f;
        float tr = 255f * (0.5f - 0.5f * k) - 6f;
        ColorMatrix ct = new ColorMatrix(new float[]{
                k * 0.98f, 0f, 0f, 0f, tr,
                0f, k, 0f, 0f, tr,
                0f, 0f, k * 1.04f, 0f, tr,
                0f, 0f, 0f, 1f, 0f});
        cm.postConcat(ct);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(in, 0f, 0f, p);

        // face slimming: cheeks are pulled in, the chin is lifted
        int n = 18;
        float[] v = new float[(n + 1) * (n + 1) * 2];
        int idx = 0;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n;
                float w = j / (float) n;
                float dx = u - 0.5f;
                float adx = Math.abs(dx);
                float wy = gauss(w, 0.66f, 0.2f);
                float wx = gauss(adx, 0.3f, 0.12f) * (1f - sstep(0.34f, 0.5f, adx));
                float push = 0.085f * wy * wx;
                float jaw = gauss(w, 0.92f, 0.1f) * gauss(adx, 0.12f, 0.3f);
                v[idx++] = (u - Math.signum(dx) * push) * s;
                v[idx++] = (w - 0.035f * jaw) * s;
            }
        }
        c.drawBitmapMesh(in, n, n, v, 0, null, 0, p);

        // light and shadow: shadow under the cheekbones, highlight on them, dark jaw edge, vignette
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            float cx = s * (0.5f + sgn * 0.265f);
            float cy = s * 0.68f;
            c.save();
            c.scale(1f, 1.35f, cx, cy);
            sp.setShader(new RadialGradient(cx, cy, s * 0.17f,
                    new int[]{0x66000000, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, s * 0.17f, sp);
            c.restore();
            float hx = s * (0.5f + sgn * 0.30f);
            float hy = s * 0.56f;
            sp.setShader(new RadialGradient(hx, hy, s * 0.1f,
                    new int[]{0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, s * 0.1f, sp);
        }
        sp.setShader(new LinearGradient(0f, s * 0.78f, 0f, s, 0x00000000, 0x70000000, Shader.TileMode.CLAMP));
        c.drawRect(0f, s * 0.78f, s, s, sp);
        sp.setShader(new RadialGradient(s / 2f, s / 2f, s * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x66000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, s, s, sp);
        return out;
    }
}

/** The smash and the chime, made on the fly: no sound files needed. */
final class HammerSound {

    private static final int RATE = 22050;
    private static short[] hitBuf;
    private static short[] chimeBuf;

    private HammerSound() {
    }

    private static synchronized short[] buffer(int kind) {
        if (kind == 0) {
            if (hitBuf == null) {
                hitBuf = make(0);
            }
            return hitBuf;
        }
        if (chimeBuf == null) {
            chimeBuf = make(1);
        }
        return chimeBuf;
    }

    private static short[] make(int kind) {
        float secs = kind == 0 ? 0.45f : 1.5f;
        int n = (int) (RATE * secs);
        short[] out = new short[n];
        Random r = new Random(kind + 11);
        double phase = 0.0;
        float prev = 0f;
        float[] notes = {523.25f, 659.25f, 783.99f, 1046.5f};
        for (int i = 0; i < n; i++) {
            float tt = i / (float) RATE;
            float noise = r.nextFloat() * 2f - 1f;
            float hp = noise - prev;
            prev = noise;
            float v;
            if (kind == 0) {
                float f = 50f + 120f * (float) Math.exp(-tt * 25f);
                phase += 2.0 * Math.PI * f / RATE;
                float thump = (float) Math.sin(phase) * (float) Math.exp(-tt * 9f);
                float crack = hp * (float) Math.exp(-tt * 45f);
                float crunch = tt > 0.06f ? hp * (float) Math.exp(-(tt - 0.06f) * 60f) * 0.45f : 0f;
                v = 0.95f * thump + 0.55f * crack + crunch;
            } else {
                float f = 45f + 70f * (float) Math.exp(-tt * 12f);
                phase += 2.0 * Math.PI * f / RATE;
                v = 0.8f * (float) Math.sin(phase) * (float) Math.exp(-tt * 5f);
                for (int q = 0; q < notes.length; q++) {
                    float st = 0.10f + q * 0.15f;
                    if (tt >= st) {
                        float e = (float) Math.exp(-(tt - st) * 4.5f);
                        double w = 2.0 * Math.PI * notes[q] * (tt - st);
                        v += 0.32f * e * ((float) Math.sin(w) + 0.3f * (float) Math.sin(2.0 * w));
                    }
                }
                v += 0.05f * hp * (float) Math.sin(Math.PI * Math.min(1f, tt / secs));
            }
            if (i > n - 300) {
                v *= (n - i) / 300f;
            }
            v = Math.max(-1f, Math.min(1f, v));
            out[i] = (short) (v * 30000f);
        }
        return out;
    }

    static void play(final int kind) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    short[] buf = buffer(kind);
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            buf.length * 2, AudioTrack.MODE_STATIC);
                    track.write(buf, 0, buf.length);
                    track.play();
                    Thread.sleep(kind == 0 ? 700L : 1800L);
                } catch (Throwable ignored) {
                    // no sound is better than a crash
                } finally {
                    if (track != null) {
                        try {
                            track.release();
                        } catch (Throwable ignored) {
                            // nothing to do
                        }
                    }
                }
            }
        }).start();
    }
}
