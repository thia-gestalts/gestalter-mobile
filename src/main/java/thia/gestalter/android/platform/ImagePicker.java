package thia.gestalter.android.platform;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.Locale;
import thia.gestalter.core.Notes;

public final class ImagePicker {
    public static final int REQUEST = 10;

    public void open(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        activity.startActivityForResult(intent, REQUEST);
    }

    public Notes.NewImage selected(ContentResolver resolver, Uri uri) {
        String mime = resolver.getType(uri);
        if (mime == null) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
        }
        if (mime == null) throw new IllegalArgumentException("This provider did not identify the image type.");
        return new Notes.NewImage(mime.toLowerCase(Locale.ROOT), () -> resolver.openInputStream(uri));
    }

    public static Bitmap thumbnail(Notes.OpenImage source) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = source.open()) { BitmapFactory.decodeStream(input, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Image preview unavailable.");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 800)
            options.inSampleSize *= 2;
        try (InputStream input = source.open()) {
            Bitmap result = BitmapFactory.decodeStream(input, null, options);
            if (result == null) throw new IOException("Image preview unavailable.");
            return result;
        }
    }
}
