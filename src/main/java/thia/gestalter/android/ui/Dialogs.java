package thia.gestalter.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

public final class Dialogs {
    private Dialogs() {}

    public static void confirm(Context context, String message, String keep, String act, Runnable action) {
        new AlertDialog.Builder(context).setMessage(message).setNegativeButton(keep, null)
                .setPositiveButton(act, (dialog, which) -> action.run()).show();
    }

    public static void image(Context context, Bitmap bitmap) {
        ImageView full = new ImageView(context);
        full.setContentDescription("Attached image");
        full.setAdjustViewBounds(true);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setImageBitmap(bitmap);
        new AlertDialog.Builder(context).setTitle("Image").setView(full).setPositiveButton("Done", null).show();
    }
}
