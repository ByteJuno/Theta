package io.github.bytejuno.thetacore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Fixed signed test assets; Java keeps the separate provider independent of host libraries. */
public final class S1PayloadProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))
                || !"r".equals(mode)
                || !"io.github.bytejuno.thetacore.s1.test.payloads".equals(uri.getAuthority())
                || !("/v1".equals(uri.getPath()) || "/v2".equals(uri.getPath()) || "/language-data".equals(uri.getPath())
                    || "/language-deleted".equals(uri.getPath()) || "/language-denied".equals(uri.getPath()))
                || uri.getQuery() != null) throw new FileNotFoundException("Fixed emulator assets only");
        if ("/language-deleted".equals(uri.getPath())) {
            File deleted = new File(getContext().getCacheDir(), "issue82-deleted.apk");
            if (deleted.exists() && !deleted.delete()) throw new FileNotFoundException("Cannot delete fixture");
            return ParcelFileDescriptor.open(deleted, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if ("/language-denied".equals(uri.getPath())) throw new SecurityException("issue82 provider permission revoked fixture");
        if ("/language-data".equals(uri.getPath())) {
            File zip = new File(getContext().getCacheDir(), "issue82-language-data.zip");
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
                String[] paths = {"theta-plugin.json", "skills/example/SKILL.md"};
                String[] texts = {"{\"schema\":1,\"type\":\"data\",\"id\":\"test.issue82.data\",\"name\":\"Language fixture\",\"version\":\"1.0\",\"versionCode\":1,\"apiLevel\":1}",
                    "Synthetic offline language fixture. No credentials or network."};
                for (int i = 0; i < paths.length; i++) {
                    out.putNextEntry(new java.util.zip.ZipEntry(paths[i]));
                    out.write(texts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            } catch (java.io.IOException failure) { throw new FileNotFoundException(failure.toString()); }
            return ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        String name = "code-" + uri.getLastPathSegment() + ".apk";
        File target = new File(getContext().getCacheDir(), name);
        File temporary = new File(getContext().getCacheDir(), name + ".preparing");
        try {
            try (InputStream input = getContext().getAssets().open(name);
                 OutputStream output = Files.newOutputStream(temporary.toPath())) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n);
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.IOException failure) {
            FileNotFoundException wrapped = new FileNotFoundException(failure.toString());
            wrapped.initCause(failure);
            throw wrapped;
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
