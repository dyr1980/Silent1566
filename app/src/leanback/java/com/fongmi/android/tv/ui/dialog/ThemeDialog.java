package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.theme.ThemeColorPickerDialog;
import com.fongmi.android.tv.theme.ThemeController;
import com.fongmi.android.tv.theme.ThemeEditor;
import com.fongmi.android.tv.theme.ThemeMode;
import com.fongmi.android.tv.theme.ThemePreviewView;
import com.fongmi.android.tv.theme.ThemeProfile;
import com.fongmi.android.tv.theme.ThemeProfileStore;
import com.fongmi.android.tv.theme.ThemeResolver;
import com.fongmi.android.tv.theme.ThemeTokens;
import com.fongmi.android.tv.theme.WebHtvAlertDialogBuilder;

/**
 * B-safe theme editor: 13 colour slots, 3 opacity slots, light/dark switching,
 * live preview, apply/cancel/reset.
 *
 * <p>All mutations go through {@link ThemeEditor}; nothing is persisted until the
 * user presses apply, and the panel is rebuilt from the draft after every change so
 * the displayed values can never drift from what would be saved.
 */
public final class ThemeDialog extends DialogFragment implements ThemePreviewView.Callbacks {

    private static final int[] PRESET_LABELS = {
            R.string.theme_editor_preset_default,
            R.string.theme_editor_preset_wallpaper,
            R.string.theme_editor_preset_blue,
            R.string.theme_editor_preset_teal,
            R.string.theme_editor_preset_green,
            R.string.theme_editor_preset_orange,
            R.string.theme_editor_preset_red,
            R.string.theme_editor_preset_purple,
    };
    private static final String[] PRESET_SOURCES = {
            ThemeProfile.SEED_NONE,
            ThemeProfile.SEED_WALLPAPER,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
            ThemeProfile.SEED_CUSTOM,
    };
    private static final String[] PRESET_COLORS = {
            null, null, "#0B57D0", "#00897B", "#146C2E", "#FB8C00", "#B3261E", "#8E24AA",
    };

    private ThemeEditor editor;
    private boolean dark;
    private LinearLayout panel;
    private LinearLayout rowPresets;
    private TextView status;

    public static void show(Fragment fragment) {
        new ThemeDialog().show(fragment.getChildFragmentManager(), ThemeDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        editor = ThemeEditor.load();
        dark = isDarkNow();
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildModeRow());
        root.addView(buildPresetRow());

        status = new TextView(requireContext());
        status.setPadding(dp(8), dp(6), dp(8), dp(4));
        root.addView(status);

        ScrollView scroll = new ScrollView(requireContext());
        panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        rebuildPanel();

        return new WebHtvAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_theme_color)
                .setView(root)
                .setPositiveButton(R.string.theme_editor_apply, (dialog, which) -> applyDraft())
                .setNeutralButton(R.string.theme_editor_reset, null)
                .setNegativeButton(R.string.theme_editor_cancel, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Button reset = ((androidx.appcompat.app.AlertDialog) dialog).getButton(Dialog.BUTTON_NEUTRAL);
        reset.setOnClickListener(view -> {
            ThemeProfileStore.ApplyResult result = editor.reset();
            if (!result.success()) {
                setStatus(result.error());
                return;
            }
            editor = ThemeEditor.load();
            rebuildPanel();
            setStatus(getString(R.string.theme_editor_reset_done));
            if (getParentFragment() instanceof AppearanceDialog appearance) appearance.onThemeProfileApplied();
        });
    }

    private View buildModeRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(2));

        // Platform widgets created from the Activity context carry no Material role, so they
        // must be coloured explicitly or they keep the framework default (Material's
        // #49454F) and become unreadable once the user picks a dark custom surface.
        TextView label = new TextView(requireContext());
        label.setText(R.string.theme_editor_edit_mode);
        label.setTextColor(ThemeController.current().colorOnSurface());
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button toggle = new Button(requireContext());
        toggle.setAllCaps(false);
        toggle.setText(dark ? R.string.theme_editor_dark : R.string.theme_editor_light);
        toggle.setTextColor(ThemeController.current().colorOnSurface());
        toggle.setBackground(outlinedPill());
        toggle.setOnClickListener(view -> {
            dark = !dark;
            toggle.setText(dark ? R.string.theme_editor_dark : R.string.theme_editor_light);
            rebuildPanel();
        });
        row.addView(toggle);
        return row;
    }

    private View buildPresetRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
        rowPresets = new LinearLayout(requireContext());
        rowPresets.setOrientation(LinearLayout.HORIZONTAL);
        rowPresets.setPadding(dp(8), dp(2), dp(8), dp(2));
        fillPresetRow();
        scroll.addView(rowPresets);
        return scroll;
    }

    /** Rebuilds the preset chips so the active seed always shows a visible highlight. */
    private void fillPresetRow() {
        if (rowPresets == null) return;
        rowPresets.removeAllViews();
        ThemeProfile profile = editor.draft();
        String activeSource = ThemeProfile.normalizeSeedSource(profile.seedSource);
        String activeColor = profile.seedColor;
        for (int i = 0; i < PRESET_LABELS.length; i++) {
            String source = PRESET_SOURCES[i];
            String color = PRESET_COLORS[i];
            boolean active = activeSource.equals(source)
                    && (!ThemeProfile.SEED_CUSTOM.equals(source)
                        || String.valueOf(activeColor).equalsIgnoreCase(color));
            rowPresets.addView(createPresetButton(getString(PRESET_LABELS[i]), source, color, active));
        }
    }

    private Button createPresetButton(String label, String seedSource, String seedColor, boolean active) {
        boolean colorPreview = ThemeProfile.SEED_CUSTOM.equals(seedSource);
        int fill = colorPreview ? presetColor(seedSource, seedColor) : neutralPresetBackground();
        Button button = new Button(requireContext());
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(colorPreview ? readableOn(fill) : ThemeController.current().colorOnSurface());
        button.setBackground(presetBackground(fill, active, colorPreview));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        button.setOnClickListener(view -> {
            ThemeEditor.Result result = editor.setSeed(seedSource, seedColor);
            if (!result.success()) {
                setStatus(result.error());
                return;
            }
            fillPresetRow();
            rebuildPanel();
        });
        return button;
    }

    /** Resolves the preset's own resolved primary color so the chip previews reality. */
    private int presetColor(String seedSource, String seedColor) {
        ThemeEditor probeEditor = new ThemeEditor(editor.draft());
        ThemeEditor.Result seedResult = probeEditor.setSeed(seedSource, seedColor);
        if (!seedResult.success()) return ThemeController.current().colorPrimary();
        ThemeProfile probe = probeEditor.draft();
        try {
            int seedColorValue = ThemeProfileStore.legacyThemeColor(probe);
            ThemeTokens tokens = ThemeResolver.resolve(dark ? ThemeMode.DARK : ThemeMode.LIGHT,
                    ThemePreviewView.seedOf(probe), seedColorValue,
                    Setting.getWallColor(), probe, null, dark);
            return tokens.colorPrimary();
        } catch (RuntimeException ignored) {
            return ThemeController.current().colorPrimary();
        }
    }

    private int neutralPresetBackground() {
        return ThemeController.current().colorSurfaceContainerHighest();
    }

    /**
     * A themed pill for the framework {@code Button}, which ships its own light background
     * and would otherwise become white-on-white once its text follows the theme.
     */
    private GradientDrawable outlinedPill() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(18));
        shape.setColor(ThemeController.current().colorSurfaceContainer());
        shape.setStroke(dp(1), ThemeController.current().colorOutline());
        return shape;
    }

    private GradientDrawable presetBackground(int fill, boolean active, boolean colorPreview) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(18));
        shape.setColor(fill);
        if (active) {
            shape.setStroke(dp(3), colorPreview ? readableOn(fill) : ThemeController.current().colorPrimary());
        } else if (colorPreview) {
            shape.setStroke(dp(1), ThemeController.current().colorOutline());
        }
        return shape;
    }

    private int readableOn(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255d;
        return luminance > 0.6d ? Color.BLACK : Color.WHITE;
    }

    private void rebuildPanel() {
        panel.removeAllViews();
        panel.addView(ThemePreviewView.createPanel(requireContext(), editor, dark, this));
    }

    private void setStatus(String message) {
        status.setTextColor(ThemeController.current().colorOnSurfaceVariant());
        status.setText(message == null ? "" : message);
    }

    @Override
    public void onColorSlotClicked(ThemeEditor.Slot slot, boolean slotDark) {
        String initial = editor.valueOf(slot, slotDark);
        ThemeColorPickerDialog.create(requireContext(), labelOf(slot), initial, hex -> {
            ThemeEditor.Result result = editor.set(slot, slotDark, hex, 0f);
            if (!result.success()) setStatus(result.error());
            else rebuildPanel();
        }).show();
    }

    @Override
    public void onDraftChanged() {
        rebuildPanel();
        setStatus(getString(R.string.theme_editor_dirty));
    }

    private void applyDraft() {
        ThemeProfileStore.ApplyResult result = editor.apply();
        if (!result.success()) {
            setStatus(getString(R.string.theme_editor_save_failed, result.error()));
            return;
        }
        if (getParentFragment() instanceof AppearanceDialog appearance) appearance.onThemeProfileApplied();
        dismissAllowingStateLoss();
        RefreshEvent.theme();
    }

    private boolean isDarkNow() {
        return ThemeController.isNight(requireContext());
    }

    private String labelOf(ThemeEditor.Slot slot) {
        return getString(ThemePreviewView.labelOf(slot));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
