package com.fongmi.android.tv.theme;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.fongmi.android.tv.R;

/**
 * Live preview plus the shared 16-slot editor panel.
 *
 * <p>The panel is pure code so mobile and leanback render the same contract
 * without extra layout resources. Every mutation goes through {@link ThemeEditor},
 * so an invalid value can never reach the draft, and the panel is rebuilt from the
 * draft after each edit so the displayed state can never drift.
 */
public final class ThemePreviewView extends LinearLayout {

    /** Host callbacks; the dialog owns the picker and the apply/cancel actions. */
    public interface Callbacks {
        void onColorSlotClicked(ThemeEditor.Slot slot, boolean dark);

        void onDraftChanged();
    }

    private static final ThemeEditor.Slot[] COLOR_SLOTS = {
            ThemeEditor.Slot.PRIMARY,
            ThemeEditor.Slot.PRIMARY_CONTAINER,
            ThemeEditor.Slot.SECONDARY_CONTAINER,
            ThemeEditor.Slot.FOCUS,
            ThemeEditor.Slot.SURFACE,
            ThemeEditor.Slot.SURFACE_CONTAINER,
            ThemeEditor.Slot.SURFACE_CONTAINER_HIGH,
            ThemeEditor.Slot.ON_SURFACE,
            ThemeEditor.Slot.ON_SURFACE_VARIANT,
            ThemeEditor.Slot.OUTLINE,
            ThemeEditor.Slot.ERROR,
            ThemeEditor.Slot.SUCCESS,
            ThemeEditor.Slot.WARNING,
    };

    private static final ThemeEditor.Slot[] OPACITY_SLOTS = {
            ThemeEditor.Slot.SCRIM_OPACITY,
            ThemeEditor.Slot.DIALOG_OPACITY,
            ThemeEditor.Slot.OVERLAY_OPACITY,
    };

    private TextView title;
    private TextView body;
    private TextView secondary;
    private Button filled;
    private Button tonal;
    private LinearLayout card;
    private LinearLayout dialog;
    private TextView opacity;

    public ThemePreviewView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        int pad = dp(context, 14);
        setPadding(pad, pad, pad, pad);
        title = text(context, 17);
        body = text(context, 15);
        secondary = text(context, 13);
        opacity = text(context, 12);
        title.setText(context.getString(R.string.theme_editor_preview_title));
        body.setText(context.getString(R.string.theme_editor_preview_body));
        secondary.setText(context.getString(R.string.theme_editor_preview_secondary));
        filled = button(context, context.getString(R.string.theme_editor_preview_filled));
        tonal = button(context, context.getString(R.string.theme_editor_preview_tonal));
        card = surface(context, title, body);
        dialog = surface(context, title, secondary);
        addView(title);
        addView(body);
        addView(secondary);
        addView(filled, rowParams(context));
        addView(tonal, rowParams(context));
        addView(card, rowParams(context));
        addView(dialog, rowParams(context));
        addView(opacity);
    }

    /** Renders the resolved tokens; null is ignored so a partial draft still previews. */
    public void bind(ThemeTokens tokens) {
        if (tokens == null) return;
        setBackgroundColor(tokens.colorSurface());
        title.setTextColor(tokens.colorOnSurface());
        body.setTextColor(tokens.colorOnSurface());
        secondary.setTextColor(tokens.colorOnSurfaceVariant());
        opacity.setTextColor(tokens.colorOnSurfaceVariant());
        opacity.setText(String.format(java.util.Locale.US,
                "scrim %.2f · dialog %.2f · overlay %.2f",
                alphaOf(tokens.colorScrim()), tokens.dialogOpacity(), alphaOf(tokens.colorOverlayLight())));
        buttonTint(filled.getContext(), filled, tokens.colorPrimary(), tokens.colorOnPrimary(), tokens.colorPrimary());
        buttonTint(tonal.getContext(), tonal, tokens.colorSecondaryContainer(),
                tokens.colorOnSecondaryContainer(), tokens.colorOutline());
        shape(card, tokens.colorSurfaceContainer(), tokens.colorOutline());
        shape(dialog, withAlpha(tokens.colorSurfaceContainerHigh(), tokens.dialogOpacity()), tokens.colorOutline());
    }

    /**
     * Builds the full editor panel: mode and preset row, light/dark switch, the 13
     * colour slots, the 3 opacity slots and the live preview.
     */
    public static View createPanel(Context context, ThemeEditor editor, boolean dark, Callbacks callbacks) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(VERTICAL);

        root.addView(sectionLabel(context, context.getString(dark ? R.string.theme_editor_dark : R.string.theme_editor_light)));
        for (ThemeEditor.Slot slot : COLOR_SLOTS) {
            root.addView(colorRow(context, editor, slot, dark, callbacks));
        }
        for (ThemeEditor.Slot slot : OPACITY_SLOTS) {
            root.addView(opacityRow(context, editor, slot, dark, callbacks));
        }

        root.addView(sectionLabel(context, context.getString(R.string.theme_editor_preview)));
        ThemePreviewView preview = new ThemePreviewView(context);
        preview.bind(editor.preview(
                dark ? ThemeMode.DARK : ThemeMode.LIGHT,
                seedOf(editor.draft()),
                ThemeProfileValidator.parseColor(editor.draft().seedColor, 0),
                0,
                dark));
        root.addView(preview);
        return root;
    }

    /** Maps the profile seed selection onto the resolver contract. */
    public static ThemeSeed seedOf(ThemeProfile profile) {
        if (profile == null) return ThemeSeed.NONE;
        if (ThemeProfile.SEED_WALLPAPER.equals(profile.seedSource)) return ThemeSeed.WALLPAPER;
        if (ThemeProfile.SEED_CUSTOM.equals(profile.seedSource)) return ThemeSeed.EXPLICIT;
        return ThemeSeed.NONE;
    }

    private static View colorRow(Context context, ThemeEditor editor, ThemeEditor.Slot slot,
                                 boolean dark, Callbacks callbacks) {
        LinearLayout row = row(context);
        row.setClickable(true);
        row.setFocusable(true);
        String value = editor.valueOf(slot, dark);
        TextView label = text(context, 14);
        label.setText(labelOf(slot));
        label.setTextColor(ThemeController.current().colorOnSurface());
        row.addView(label, weight());

        TextView summary = text(context, 13);
        summary.setText(value == null ? context.getString(R.string.theme_editor_inherit) : value);
        summary.setGravity(Gravity.END);
        summary.setTextColor(ThemeController.current().colorOnSurfaceVariant());
        row.addView(summary, weight());

        if (value != null) {
            Button clear = new Button(context);
            clear.setText(R.string.theme_editor_clear);
            clear.setAllCaps(false);
            clear.setOnClickListener(view -> {
                editor.clear(slot, dark);
                callbacks.onDraftChanged();
            });
            row.addView(clear);
        }
        row.setOnClickListener(view -> callbacks.onColorSlotClicked(slot, dark));
        return row;
    }

    private static View opacityRow(Context context, ThemeEditor editor, ThemeEditor.Slot slot,
                                   boolean dark, Callbacks callbacks) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(VERTICAL);
        int pad = dp(context, 6);
        column.setPadding(pad, pad, pad, pad);

        TextView label = text(context, 14);
        String raw = editor.valueOf(slot, dark);
        float minimum = minimumOf(slot);
        float maximum = maximumOf(slot);
        float current = raw == null ? minimum : Float.parseFloat(raw);
        label.setText(context.getString(labelOf(slot)) + "  "
                + (raw == null ? context.getString(R.string.theme_editor_inherit) : raw));
        label.setTextColor(ThemeController.current().colorOnSurface());
        column.addView(label);

        SeekBar bar = new SeekBar(context);
        bar.setMax(100);
        bar.setProgress(Math.round((current - minimum) / (maximum - minimum) * 100f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float value = minimum + (maximum - minimum) * progress / 100f;
                ThemeEditor.Result result = editor.set(slot, dark, null, value);
                if (result.success()) callbacks.onDraftChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        column.addView(bar);

        Button clear = new Button(context);
        clear.setText(R.string.theme_editor_clear);
        clear.setEnabled(raw != null);
        clear.setAllCaps(false);
        clear.setOnClickListener(view -> {
            editor.clear(slot, dark);
            callbacks.onDraftChanged();
        });
        column.addView(clear);
        return column;
    }

    private static float minimumOf(ThemeEditor.Slot slot) {
        return switch (slot) {
            case SCRIM_OPACITY -> ThemeProfileValidator.MIN_SCRIM_OPACITY;
            case DIALOG_OPACITY -> ThemeProfileValidator.MIN_DIALOG_OPACITY;
            default -> ThemeProfileValidator.MIN_OVERLAY_OPACITY;
        };
    }

    private static float maximumOf(ThemeEditor.Slot slot) {
        return switch (slot) {
            case SCRIM_OPACITY -> ThemeProfileValidator.MAX_SCRIM_OPACITY;
            case DIALOG_OPACITY -> ThemeProfileValidator.MAX_DIALOG_OPACITY;
            default -> ThemeProfileValidator.MAX_OVERLAY_OPACITY;
        };
    }

    public static int labelOf(ThemeEditor.Slot slot) {
        return switch (slot) {
            case PRIMARY -> R.string.theme_editor_slot_primary;
            case PRIMARY_CONTAINER -> R.string.theme_editor_slot_primary_container;
            case SECONDARY_CONTAINER -> R.string.theme_editor_slot_secondary_container;
            case FOCUS -> R.string.theme_editor_slot_focus;
            case SURFACE -> R.string.theme_editor_slot_surface;
            case SURFACE_CONTAINER -> R.string.theme_editor_slot_surface_container;
            case SURFACE_CONTAINER_HIGH -> R.string.theme_editor_slot_surface_container_high;
            case ON_SURFACE -> R.string.theme_editor_slot_on_surface;
            case ON_SURFACE_VARIANT -> R.string.theme_editor_slot_on_surface_variant;
            case OUTLINE -> R.string.theme_editor_slot_outline;
            case ERROR -> R.string.theme_editor_slot_error;
            case SUCCESS -> R.string.theme_editor_slot_success;
            case WARNING -> R.string.theme_editor_slot_warning;
            case SCRIM_OPACITY -> R.string.theme_editor_slot_scrim_opacity;
            case DIALOG_OPACITY -> R.string.theme_editor_slot_dialog_opacity;
            case OVERLAY_OPACITY -> R.string.theme_editor_slot_overlay_opacity;
        };
    }

    private static TextView sectionLabel(Context context, String value) {
        TextView view = text(context, 13);
        view.setText(value);
        view.setPadding(0, dp(context, 10), 0, dp(context, 4));
        view.setTextColor(ThemeController.current().colorOnSurfaceVariant());
        return view;
    }

    private static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        return row;
    }

    private static LinearLayout surface(Context context, TextView a, TextView b) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(VERTICAL);
        int pad = dp(context, 12);
        group.setPadding(pad, pad, pad, pad);
        group.addView(copy(context, a));
        group.addView(copy(context, b));
        return group;
    }

    private static void buttonTint(Context context, Button button, int background, int foreground, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 20));
        shape.setColor(background);
        shape.setStroke(dp(context, 1), stroke);
        button.setBackground(shape);
        button.setTextColor(foreground);
    }

    private static void shape(View view, int background, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(view.getContext(), 12));
        shape.setColor(background);
        shape.setStroke(dp(view.getContext(), 1), stroke);
        view.setBackground(shape);
    }

    private static int withAlpha(int color, float opacity) {
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255f)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static float alphaOf(int color) {
        return ((color >>> 24) & 0xFF) / 255f;
    }

    private static TextView text(Context context, int sizeSp) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private static Button button(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private static TextView copy(Context context, TextView source) {
        TextView view = new TextView(context);
        view.setText(source.getText());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return view;
    }

    private static LayoutParams rowParams(Context context) {
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 8);
        return params;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
