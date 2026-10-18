package pet.margyt;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import cat.narezany.margyt.plugin.MargyPlugin;

/**
 * Bonesmash plugin: a hammer on top of every screen. When a profile avatar is on screen
 * you can smash it, and after three hits it is shown looksmaxxed.
 * The effect lives only on this phone's screen; nothing is uploaded or changed in the account.
 * (The class name is kept from the earlier plugins so the GitHub workflow works unchanged.)
 */
public final class PetPlugin extends MargyPlugin {

    private Activity cur;
    private ViewGroup decor;
    private PetView overlay;

    @Override
    public void onStart(Context context) {
        margyt().log("bonesmash: started");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            attach(activity);
        } catch (Throwable t) {
            margyt().log("bonesmash: attach failed: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            if (activity == cur) {
                teardown();
            }
        } catch (Throwable t) {
            margyt().log("bonesmash: detach failed: " + t);
        }
    }

    @Override
    public void onStop() {
        try {
            teardown();
        } catch (Throwable t) {
            margyt().log("bonesmash: stop failed: " + t);
        }
    }

    private void attach(Activity a) {
        View dv = a.getWindow().getDecorView();
        if (!(dv instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) dv;
        if (cur != a || decor != root || overlay == null) {
            teardown();
            cur = a;
            decor = root;
            overlay = new PetView(margyt().context());
        }
        if (overlay.getParent() != root) {
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        overlay.bringToFront();
    }

    private void teardown() {
        if (overlay != null) {
            overlay.stop();
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        }
        overlay = null;
        decor = null;
        cur = null;
    }
}
