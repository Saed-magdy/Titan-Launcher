package com.dongfeng.shinecockpit;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Titan Launcher GitHub In-App Auto Updater
 * Checks latest releases from GitHub API without blocking the UI or consuming extra resources.
 */
public class GitHubUpdateChecker {
    private static final String TAG = "TitanUpdater";
    /** The file type Android uses to recognise an installable app package (APK). */
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String REPO_API = "https://api.github.com/repos/Saed-magdy/Titan-Launcher/releases/latest";

    public static void checkForUpdates(final Context context, final boolean silentIfUpToDate) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // The installed version is read from the app itself (it comes from
                    // android:versionName in AndroidManifest.xml). There used to be a typed-in
                    // copy of the number here as a backup; every release someone had to remember
                    // to change it, and when they forgot, the updater compared against the wrong
                    // number. Now the manifest is the ONLY place the version is written.
                    String currentVersion = null;
                    try {
                        PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                        currentVersion = pInfo.versionName;
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Could not read the installed version: " + e.getMessage());
                    }
                    if (currentVersion == null || currentVersion.trim().isEmpty()) {
                        // Without knowing our own version we cannot compare safely, so we do not
                        // offer an update at all (better than offering the same version forever).
                        if (!silentIfUpToDate) {
                            showToast(context, "تعذر معرفة إصدار اللانشر الحالي.");
                        }
                        return;
                    }

                    URL url = new URL(REPO_API);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "TitanLauncher-App");
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject release = new JSONObject(sb.toString());
                        final String latestTag = release.getString("tag_name").replace("v", "").replace("V", "").trim();
                        final String releaseNotes = release.optString("body", "تحسينات عامة وإصلاح أخطاء.");

                        String downloadUrl = null;
                        if (release.has("assets")) {
                            JSONArray assets = release.getJSONArray("assets");
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                String name = asset.getString("name");
                                if (name.toLowerCase().endsWith(".apk")) {
                                    downloadUrl = asset.getString("browser_download_url");
                                    break;
                                }
                            }
                        }

                        final String finalDownloadUrl = downloadUrl;
                        final String finalCurVer = currentVersion;

                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalDownloadUrl != null && isNewerVersion(finalCurVer, latestTag)) {
                                    showUpdateDialog(context, latestTag, releaseNotes, finalDownloadUrl);
                                } else {
                                    if (!silentIfUpToDate) {
                                        Toast.makeText(context, "اللانشر على أحدث إصدار بالفعل (v" + finalCurVer + ")", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }
                        });
                    } else {
                        if (!silentIfUpToDate) {
                            showToast(context, "تعذر الاتصال بـ GitHub للتحقق من التحديثات.");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Update check failed: " + e.getMessage());
                    if (!silentIfUpToDate) {
                        showToast(context, "خطأ في فحص التحديثات، تأكد من الاتصال بالإنترنت.");
                    }
                }
            }
        }).start();
    }

    private static boolean isNewerVersion(String current, String latest) {
        try {
            String[] currParts = current.split("\\.");
            String[] lateParts = latest.split("\\.");
            int length = Math.max(currParts.length, lateParts.length);
            for (int i = 0; i < length; i++) {
                int c = i < currParts.length ? Integer.parseInt(currParts[i].replaceAll("[^0-9]", "")) : 0;
                int l = i < lateParts.length ? Integer.parseInt(lateParts[i].replaceAll("[^0-9]", "")) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Version parse error: " + e.getMessage());
        }
        return false;
    }

    private static void showUpdateDialog(final Context context, final String newVersion, final String notes, final String downloadUrl) {
        new AlertDialog.Builder(context)
                .setTitle("🚀 تحديث جديد متاح (v" + newVersion + ")")
                .setMessage("ملاحظات الإصدار الجديد:\n\n" + notes + "\n\nهل تريد تنزيل التحديث وتثبيته الآن؟")
                .setPositiveButton("تحديث الآن", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        downloadAndInstall(context, downloadUrl, newVersion);
                    }
                })
                .setNegativeButton("لاحقاً", null)
                .setCancelable(true)
                .show();
    }

    /**
     * Downloads the new APK and then opens Android's installer screen.
     *
     * In plain words, the old version of this method had three problems on Android 7 and newer:
     *
     *  1. It handed the installer a raw file path ("file://..."). Since Android 7 the system
     *     treats that as unsafe and crashes the app instead (FileUriExposedException).
     *     Fix: we ask the DownloadManager itself for a "content://" link to the file. That is
     *     the modern, permitted way to let another app (the installer) read our download.
     *
     *  2. Android 8 refuses to open the installer unless the app has declared the
     *     REQUEST_INSTALL_PACKAGES permission (added in AndroidManifest.xml) AND the user
     *     has allowed "Install unknown apps" for the launcher. If that switch is off, we now
     *     send the user straight to that settings page instead of failing silently.
     *
     *  3. It tried to install even when the download had failed (no internet, file deleted).
     *     Fix: we check the download's status first and show a clear message if it failed.
     *
     * We also save the file in the launcher's own folder instead of the public Downloads
     * folder, so no storage permission is needed and old copies don't pile up.
     */
    private static void downloadAndInstall(final Context context, final String url, final String version) {
        // Use the application-wide context, not the screen (Activity) that opened the dialog.
        // The download can finish minutes later, after that screen is gone; holding on to a
        // dead screen wastes memory and can crash when we try to show a message on it.
        final Context appContext = context.getApplicationContext();
        try {
            final DownloadManager manager = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                Toast.makeText(appContext, "خدمة DownloadManager غير متاحة في النظام", Toast.LENGTH_SHORT).show();
                return;
            }

            String fileName = "Titan_Launcher_v" + version + ".apk";

            // Delete any leftover copy from an earlier attempt so we never install an old file.
            File oldCopy = new File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
            if (oldCopy.exists()) {
                oldCopy.delete();
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Titan Launcher v" + version);
            request.setDescription("جاري تنزيل التحديث الجديد...");
            request.setMimeType(APK_MIME_TYPE);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // The launcher's private folder on storage: needs no extra permission.
            request.setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName);

            Toast.makeText(appContext, "بدأ تنزيل التحديث في الخلفية...", Toast.LENGTH_LONG).show();
            final long downloadId = manager.enqueue(request);

            // Android tells every listening app when *any* download finishes.
            // We listen, and react only when it is our download (matching downloadId).
            appContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != downloadId) {
                        return; // someone else's download, not ours
                    }
                    try {
                        appContext.unregisterReceiver(this); // stop listening, we got our answer
                    } catch (Exception e) {
                        Log.w(TAG, "Could not unregister download receiver: " + e.getMessage());
                    }

                    if (!isDownloadSuccessful(manager, downloadId)) {
                        Toast.makeText(appContext, "فشل تنزيل التحديث، حاول مرة أخرى.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // A safe "content://" link the installer is allowed to read (see note 1 above).
                    Uri apkUri = manager.getUriForDownloadedFile(downloadId);
                    if (apkUri == null) {
                        Toast.makeText(appContext, "تعذر العثور على ملف التحديث بعد التنزيل.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // See note 2 above: the user must allow this launcher to install apps.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && !appContext.getPackageManager().canRequestPackageInstalls()) {
                        Toast.makeText(appContext, "اسمح للانشر بتثبيت التطبيقات ثم اضغط تحديث مرة أخرى.", Toast.LENGTH_LONG).show();
                        Intent allowIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + appContext.getPackageName()));
                        allowIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        appContext.startActivity(allowIntent);
                        return;
                    }

                    Toast.makeText(appContext, "اكتمل التنزيل! جاري فتح التثبيت...", Toast.LENGTH_SHORT).show();
                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, APK_MIME_TYPE);
                    // NEW_TASK: required when opening a screen from outside an Activity.
                    // GRANT_READ_URI_PERMISSION: lets the installer read the content:// link.
                    installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    appContext.startActivity(installIntent);
                }
            }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        } catch (Exception e) {
            Log.e(TAG, "Download error: " + e.getMessage(), e);
            Toast.makeText(appContext, "فشل بدء التنزيل: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Asks the DownloadManager whether our download really finished, or failed part way. */
    private static boolean isDownloadSuccessful(DownloadManager manager, long downloadId) {
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                return status == DownloadManager.STATUS_SUCCESSFUL;
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not read download status: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return false;
    }

    private static void showToast(final Context context, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
