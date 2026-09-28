package com.fongmi.android.tv.theme;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;

import java.util.Locale;

/**
 * Colour picker with both hue/saturation/value sliders and exact hex entry.
 *
 * <p>Values are validated with {@link ThemeProfileValidator#normalizeColor} before
 * the callback fires, so an invalid string can never reach the editor draft. The
 * dialog itself owns the parsing; callers only receive a normalised {@code #RRGGBB}.
 */
public final class ThemeColorPickerDialog {

    /** Receives the validated colour when the user confirms. */
    public interface Listener {
        void onColorPicked(String hex);
    }

    private ThemeColorPickerDialog() {
    }

    public static AlertDialog create(Context context, String title, String initial, Listener listener) {
        int start = ThemeProfileValidator.parseColor(initial, 0xFF000000);
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(start, hsv);

        LinearLayout root = column(context);
        View swatch = new View(context);
        root.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 44)));

        SeekBar hue = slider(context, root, 360, Math.round(hsv[0]));
        SeekBar saturation = slider(context, root, 100, Math.round(hsv[1] * 100f));
        SeekBar brightness = slider(context, root, 100, Math.round(hsv[2] * 100f));

        // These are platform widgets created from the Activity context, so they carry no
        // Material role and would otherwise keep the framework default colour (the editor
        // rendered them as Material's #49454F on whatever surface the user picked).
        TextView hexLabel = new TextView(context);
        hexLabel.setText(R.string.theme_editor_hex_label);
        hexLabel.setTextColor(ThemeController.current().colorOnSurface());
        root.addView(hexLabel);

        EditText hexInput = new EditText(context);
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hexInput.setSingleLine(true);
        hexInput.setHint(R.string.theme_editor_hex_hint);
        hexInput.setTextColor(ThemeController.current().colorOnSurface());
        hexInput.setHintTextColor(ThemeController.current().colorOnSurfaceVariant());
        root.addView(hexInput);

        TextView error = new TextView(context);
        error.setTextColor(ThemeController.current().colorError());
        root.addView(error);

        Button useHex = new Button(context);
        useHex.setText(R.string.theme_editor_hex_apply);
        useHex.setAllCaps(false);
        useHex.setTextColor(ThemeController.current().colorOnSurface());
        useHex.setBackground(outlinedPill(context));
        int pillPadding = dp(context, 10);
        useHex.setPadding(pillPadding, pillPadding, pillPadding, pillPadding);
        root.addView(useHex);

        Runnable syncFromHsv = () -> {
            int color = android.graphics.Color.HSVToColor(new float[]{hue.getProgress(), saturation.getProgress() / 100f, brightness.getProgress() / 100f});
            String hex = toHex(color);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(context, 10));
            shape.setColor(color);
            swatch.setBackground(shape);
            hexInput.setText(hex);
            error.setText("");
        };

        SeekBar.OnSeekBarChangeListener onChange = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) syncFromHsv.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        hue.setOnSeekBarChangeListener(onChange);
        saturation.setOnSeekBarChangeListener(onChange);
        brightness.setOnSeekBarChangeListener(onChange);

        useHex.setOnClickListener(view -> {
            String normalized = ThemeProfileValidator.normalizeColor(hexInput.getText().toString());
            if (normalized == null) {
                error.setText(R.string.theme_editor_hex_invalid);
                return;
            }
            float[] parsed = new float[3];
            android.graphics.Color.colorToHSV(ThemeProfileValidator.parseColor(normalized, 0xFF000000), parsed);
            hue.setProgress(Math.round(parsed[0]));
            saturation.setProgress(Math.round(parsed[1] * 100f));
            brightness.setProgress(Math.round(parsed[2] * 100f));
            syncFromHsv.run();
        });

        syncFromHsv.run();
        AlertDialog dialog = new WebHtvAlertDialogBuilder(context)
                .setTitle(title)
                .setView(root)
                .setPositiveButton(R.string.theme_editor_confirm, null)
                .setNegativeButton(R.string.theme_editor_cancel, null)
                .create();
        // Validate before dismissing so an invalid hex never closes the picker.
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String normalized = ThemeProfileValidator.normalizeColor(hexInput.getText().toString());
            if (normalized == null) {
                error.setText(R.string.theme_editor_hex_invalid);
                return;
            }
            listener.onColorPicked(normalized);
            dialog.dismiss();
        }));
        return dialog;
    }

    /**
     * A themed pill for the framework {@code Button}, which ships its own light background
     * and would otherwise stay white-on-white once the text colour follows the theme.
     */
    private static GradientDrawable outlinedPill(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(context, 18));
        shape.setColor(ThemeController.current().colorSurfaceContainer());
        shape.setStroke(dp(context, 1), ThemeController.current().colorOutline());
        return shape;
    }

    private static SeekBar slider(Context context, LinearLayout root, int max, int initial) {
        SeekBar bar = new SeekBar(context);
        bar.setMax(max);
        bar.setProgress(initial);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private static LinearLayout column(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    static String toHex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
