package com.fongmi.android.tv.theme;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.MaterialShapeDrawable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Controlled runtime channel for user theme overrides.
 *
 * <p>Android offers no public API to rewrite a compiled {@code ?attr/color*}
 * value, so the binder walks the already-created view tree and swaps only colors
 * that provably came from a semantic role: a view color is rewritten when it
 * exactly equals a frozen baseline role value and is not stateful. Everything
 * else - player surfaces, media, posters, brand and health colors, focus
 * selectors, unknown colors - is left untouched.
 *
 * <p>This runs on the main thread only, is a no-op when the active tokens equal
 * the baseline, and never touches a view more than once per token signature.
 */
public final class ThemeBinder {

    private static final String IGNORE_TAG = "webhtv:ignore";
    private static final String[] EXEMPT_CLASS_MARKERS = {
            "surface", "texture", "video", "player", "danmaku", "subtitle",
            "karaoke", "wall", "logo", "rating", "media",
    };

    private static final Map<View, Long> APPLIED = new WeakHashMap<>();
    private static final Map<RecyclerView, RecyclerView.OnChildAttachStateChangeListener> WATCHED = new WeakHashMap<>();
    private static final Map<View, int[]> ROOTS = new WeakHashMap<>();
    private static final Map<ColorStateList, ColorStateList> REBUILT = new WeakHashMap<>();

    private static long cachedSignature;

    private static volatile long lastBindMillis;
    private static volatile int lastBoundViews;
    private static volatile int lastWalkedViews;

    private ThemeBinder() {
    }

    /** Elapsed time of the most recent non-trivial bind, for the performance gate. */
    public static long lastBindMillis() {
        return lastBindMillis;
    }

    /** Number of views rewritten by the most recent non-trivial bind. */
    public static int lastBoundViews() {
        return lastBoundViews;
    }

    public static void bind(View root, ThemeTokens baseline, ThemeTokens active) {
        if (root == null || baseline == null || active == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        if (baseline.equals(active)) {
            // Default profile: the static Layer 1 theme is already correct, so the
            // binder must cost nothing and change nothing.
            lastBindMillis = 0L;
            lastBoundViews = 0;
            return;
        }
        long started = SystemClock.elapsedRealtimeNanos();
        long signature = signature(active);
        if (signature != cachedSignature) {
            REBUILT.clear();
            cachedSignature = signature;
        }
        ThemeColorIndex index = ThemeColorIndex.of(baseline);
        int bound = walk(root, index, active, signature);
        watchRoot(root, index, active, signature);
        lastBoundViews = bound;
        lastBindMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L;
    }

    private static int walk(View root, ThemeColorIndex index, ThemeTokens active, long signature) {
        int bound = 0;
        int walked = 0;
        Deque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view == null) continue;
            walked++;
            if (isExempt(view)) continue;
            if (bindView(view, index, active, signature)) bound++;
            if (view instanceof RecyclerView recyclerView) watchChildren(recyclerView, index, active, signature);
            if (view instanceof ViewGroup group) {
                for (int child = 0; child < group.getChildCount(); child++) {
                    queue.add(group.getChildAt(child));
                }
            }
        }
        lastWalkedViews = walked;
        return bound;
    }

    /**
     * Fragments and async content attach after the Activity bind points. The root
     * is re-walked only when its descendant count actually changed, which keeps the
     * scroll path free of repeated traversals.
     */
    private static void watchRoot(View root, ThemeColorIndex index, ThemeTokens active, long signature) {
        if (ROOTS.containsKey(root)) return;
        int[] state = {countDescendants(root)};
        ROOTS.put(root, state);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (state[0] == countDescendants(view)) return;
            state[0] = countDescendants(view);
            walk(view, index, active, signature);
        });
    }

    private static int countDescendants(View view) {
        if (!(view instanceof ViewGroup group)) return 1;
        int count = 1;
        for (int child = 0; child < group.getChildCount(); child++) count += countDescendants(group.getChildAt(child));
        return count;
    }

    private static void watchChildren(RecyclerView recyclerView, ThemeColorIndex index, ThemeTokens active, long signature) {
        if (WATCHED.containsKey(recyclerView)) return;
        RecyclerView.OnChildAttachStateChangeListener listener = new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                walk(view, index, active, signature);
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                APPLIED.remove(view);
            }
        };
        WATCHED.put(recyclerView, listener);
        recyclerView.addOnChildAttachStateChangeListener(listener);
    }

    private static boolean bindView(View view, ThemeColorIndex index, ThemeTokens active, long signature) {
        Long applied = APPLIED.get(view);
        if (applied != null && applied == signature) return false;
        ThemeRole explicit = ThemeRole.fromTag(view.getTag());
        boolean changed = false;
        if (view instanceof TextView textView) changed |= bindText(textView, explicit, index, active);
        if (view instanceof ImageView imageView) changed |= bindImageTint(imageView, explicit, index, active);
        if (view instanceof MaterialCardView cardView) changed |= bindCard(cardView, explicit, index, active);
        if (view instanceof MaterialButton button) changed |= bindButton(button, explicit, index, active);
        changed |= bindBackgroundTint(view, explicit, index, active);
        changed |= bindBackground(view, explicit, index, active);
        if (changed) APPLIED.put(view, signature);
        return changed;
    }

    private static boolean bindText(TextView view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        boolean changed = false;
        ColorStateList colors = rewrite(view.getTextColors(), explicit, index, active);
        if (colors != null) {
            view.setTextColor(colors);
            changed = true;
        }
        ColorStateList hint = rewrite(view.getHintTextColors(), explicit, index, active);
        if (hint != null) {
            view.setHintTextColor(hint);
            changed = true;
        }
        return changed;
    }

    private static boolean bindImageTint(ImageView view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        ColorStateList tint = rewrite(view.getImageTintList(), explicit, index, active);
        if (tint == null) return false;
        view.setImageTintList(tint);
        return true;
    }

    private static boolean bindCard(MaterialCardView view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        boolean changed = false;
        ColorStateList background = rewrite(view.getCardBackgroundColor(), explicit, index, active);
        if (background != null) {
            view.setCardBackgroundColor(background);
            changed = true;
        }
        ColorStateList stroke = rewrite(ColorStateList.valueOf(view.getStrokeColor()),
                index.uniqueRoleFor(view.getStrokeColor()) == null ? ThemeRole.OUTLINE : null, index, active);
        if (stroke != null) {
            view.setStrokeColor(stroke);
            changed = true;
        }
        return changed;
    }

    /**
     * Filled and outlined Material buttons keep their fill in a background tint and
     * their border in a stroke colour rather than in a GradientDrawable, so the
     * generic drawable path never reached them and a themed button stayed on the
     * compiled palette. Both channels are still resolved from the same semantic
     * attributes and obey the identical exact-match rule.
     */
    private static boolean bindButton(MaterialButton view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        boolean changed = false;
        ColorStateList stroke = rewrite(view.getStrokeColor(), explicit, index, active);
        if (stroke != null) {
            view.setStrokeColor(stroke);
            changed = true;
        }
        ColorStateList icon = rewrite(view.getIconTint(), explicit, index, active);
        if (icon != null) {
            view.setIconTint(icon);
            changed = true;
        }
        return changed;
    }

    /**
     * Covers every view that fills itself through {@code app:backgroundTint} - the
     * Material button family, chips, FABs and text-input boxes - with the same
     * "only an exact baseline role colour" guard the rest of the binder uses.
     */
    private static boolean bindBackgroundTint(View view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        ColorStateList tint = rewrite(view.getBackgroundTintList(), explicit, index, active);
        if (tint == null) return false;
        view.setBackgroundTintList(tint);
        return true;
    }

    private static boolean bindBackground(View view, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        return bindDrawable(view.getBackground(), explicit, index, active);
    }

    /**
     * Rewrites a window-level background drawable.
     *
     * <p>{@code MaterialAlertDialogBuilder} builds the dialog panel as a
     * {@link MaterialShapeDrawable} wrapped in an {@link InsetDrawable} and hands it to
     * {@code Window.setBackgroundDrawable}. That drawable belongs to the window, not to
     * any {@code View}, so the ordinary view-tree walk can never reach it - which is why
     * a themed dialog changed its text but kept the compiled panel colour. The builder
     * exposes the very same instance through {@code getBackground()}, so it is rewritten
     * here through one shared code path instead of a second colour model.
     */
    public static boolean bindWindowBackground(Drawable drawable, ThemeTokens baseline, ThemeTokens active) {
        if (drawable == null || baseline == null || active == null) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        if (baseline.equals(active)) return false;
        boolean changed = bindDrawable(drawable, null, ThemeColorIndex.of(baseline), active);
        // dialogOpacity is documented as "the dialog/BottomSheet shell only, never the text
        // alpha", so it is applied to this window background and nowhere else. Without this
        // the editor's dialog-opacity slider changed only the preview swatch and the Web
        // snapshot while real dialogs stayed fully opaque.
        changed |= applyShellOpacity(drawable, active.dialogOpacity());
        return changed;
    }

    /**
     * Scales the alpha of a window background fill, preserving whatever transparency the
     * drawable already had. A shell opacity of 1.0 is the shipped default and must stay a
     * strict no-op so the frozen palette path remains byte-identical.
     */
    private static boolean applyShellOpacity(Drawable drawable, float opacity) {
        if (drawable == null || opacity >= 1f) return false;
        if (opacity < 0f) return false;
        if (drawable instanceof InsetDrawable inset) {
            return applyShellOpacity(inset.getDrawable(), opacity);
        }
        if (drawable instanceof MaterialShapeDrawable shape) {
            ColorStateList fill = shape.getFillColor();
            if (fill == null) return false;
            int original = fill.getDefaultColor();
            int target = scaleAlpha(original, opacity);
            if (target == original) return false;
            shape.setFillColor(ColorStateList.valueOf(target));
            return true;
        }
        if (drawable instanceof GradientDrawable shape) {
            ColorStateList color = shape.getColor();
            if (color == null) return false;
            int original = color.getDefaultColor();
            int target = scaleAlpha(original, opacity);
            if (target == original) return false;
            shape.setColor(target);
            return true;
        }
        return false;
    }

    /** Package-private so the alpha arithmetic can be asserted without an Android device. */
    static int scaleAlpha(int color, float opacity) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, opacity)));
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /**
     * Rewrites the solid fill of a background drawable, unwrapping the transparent
     * wrappers Android and Material put around real panels.
     *
     * <p>Two families are covered by the same exact-match rule as everything else:
     * {@link GradientDrawable} (classic XML shapes) and
     * {@link MaterialShapeDrawable} (Chip, text-input box, bottom sheet and the
     * AlertDialog panel). Material's alert background is additionally wrapped in an
     * {@link InsetDrawable} carrying the window insets, so wrappers are unwrapped
     * recursively instead of being treated as an unknown drawable.
     */
    private static boolean bindDrawable(Drawable drawable, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        if (drawable == null) return false;
        if (drawable instanceof InsetDrawable inset) {
            return bindDrawable(inset.getDrawable(), explicit, index, active);
        }
        if (drawable instanceof GradientDrawable shape) {
            ColorStateList color = rewrite(shape.getColor(), explicit, index, active);
            if (color == null) return false;
            shape.setColor(color);
            return true;
        }
        if (drawable instanceof MaterialShapeDrawable shape) {
            ColorStateList color = rewrite(shape.getFillColor(), explicit, index, active);
            if (color == null) return false;
            shape.setFillColor(color);
            return true;
        }
        return false;
    }

    /**
     * Returns a recolored copy of the list, preserving its state behaviour, or null
     * when the colour is unknown/unchanged or the state shape cannot be rebuilt.
     */
    private static ColorStateList rewrite(ColorStateList source, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        if (source == null) return null;
        if (!source.isStateful()) {
            Integer replacement = singleColor(source.getDefaultColor(), explicit, index, active);
            return replacement == null ? null : ColorStateList.valueOf(replacement);
        }
        ColorStateList cached = REBUILT.get(source);
        if (cached != null) return cached;
        int[][] states = ThemeColorIndex.stateSkeleton();
        int[] colors = new int[states.length];
        boolean changed = false;
        // Material state lists usually vary alpha only. Resolve the role from the
        // default colour first, then keep each state's original alpha so pressed /
        // disabled / focused states stay distinguishable.
        int defaultColor = source.getDefaultColor();
        Integer defaultReplacement = singleColor(defaultColor, explicit, index, active);
        for (int position = 0; position < states.length; position++) {
            int original = resolvedColor(source, states[position]);
            Integer replacement = singleColor(original, explicit, index, active);
            if (replacement == null && defaultReplacement != null && sameRgb(original, defaultColor)) {
                replacement = withAlpha(defaultReplacement, original >>> 24);
            }
            colors[position] = replacement == null ? original : replacement;
            changed |= replacement != null;
        }
        if (!changed) return null;
        // ColorStateList exposes a public (int[][], int[]) constructor, so the list
        // can be rebuilt directly without XML inflation or hidden platform APIs.
        ColorStateList rebuilt = new ColorStateList(states, colors);
        REBUILT.put(source, rebuilt);
        return rebuilt;
    }

    private static boolean sameRgb(int first, int second) {
        return (first & 0x00FFFFFF) == (second & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int resolvedColor(ColorStateList source, int[] state) {
        int color = source.getColorForState(state, source.getDefaultColor());
        return color == 0 ? source.getDefaultColor() : color;
    }

    private static Integer singleColor(int current, ThemeRole explicit, ThemeColorIndex index, ThemeTokens active) {
        if (explicit != null) {
            Integer baseline = index.baselineOf(explicit);
            int target = explicit.colorOf(active);
            if (target == 0 || target == current) return null;
            if (baseline != null && baseline != current) return null;
            return target;
        }
        return index.replacementFor(current, active);
    }

    private static boolean isExempt(View view) {
        Object tag = view.getTag();
        if (tag instanceof String value && IGNORE_TAG.equals(value.trim().toLowerCase(Locale.ROOT))) return true;
        String name = view.getClass().getName().toLowerCase(Locale.ROOT);
        for (String marker : EXEMPT_CLASS_MARKERS) {
            if (name.contains(marker)) return true;
        }
        return false;
    }

    private static long signature(ThemeTokens tokens) {
        // ThemeTokens is a content-based record; its generated hashCode is stable
        // across the desugared Android runtime. Java-16 record reflection is
        // deliberately avoided because it does not exist on Android API < 33.
        return tokens.hashCode();
    }
}
